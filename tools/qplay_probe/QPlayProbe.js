// QPlayProbe - Phase 0 协议验证探针 (Node.js 版, 零依赖)
// 1) 假装 DLNA/QPlay MediaRenderer（SSDP 应答 + NOTIFY 广播 + HTTP/SOAP 服务）
// 2) 主动 M-SEARCH 扫描局域网 UPnP 设备（探测 QQ 音乐手机/PC 端是否注册 renderer）
// 3) 全量记录 SSDP / SOAP / GENA 交互到 probe.log
// 运行: node QPlayProbe.js
'use strict';
const dgram = require('dgram');
const http = require('http');
const net = require('net');
const fs = require('fs');
const path = require('path');
const os = require('os');
const crypto = require('crypto');
const { spawn, execSync } = require('child_process');

const SSDP_PORT = 1900;
const HTTP_PORT = 49153;
const SSDP_ADDR = '239.255.255.250';
const FRIENDLY_NAME = 'QQMusicWear-Test';
const UDN = 'uuid:qmusicwear-probe-0001-0002-0003';

const logFile = path.join(__dirname, 'probe.log');
const devices = new Map(); // usn -> {name, location, devType, services}

// ---------------- 播放状态机 ----------------
let queue = [];            // SetTracksInfo 下发的歌曲数组
let queueId = '';          // qplay:// 后的队列ID
let curIndex = 0;          // 当前曲目索引
let transportState = 'NO_MEDIA_PRESENT';
let playStartTs = 0;       // Play 动作时间戳
let playToken = 0;

// GENA 订阅回调表
const callbacks = { AVTransport: [], RenderingControl: [] };
const seqMap = new Map();

function localIp() {
  const ifs = os.networkInterfaces();
  for (const k of Object.keys(ifs)) {
    for (const it of ifs[k]) {
      if (it.family === 'IPv4' && !it.internal) return it.address;
    }
  }
  return '127.0.0.1';
}
const LOCAL_IP = localIp();

function log(line) {
  const s = `[${new Date().toISOString().slice(11, 23)}] ${line}`;
  console.log(s);
  fs.appendFileSync(logFile, s + '\n');
}
function logPacket(tag, rinfo, data) {
  log(`-------- ${tag} from ${rinfo.address}:${rinfo.port} --------`);
  for (const l of data.replace(/\r\n/g, '\n').split('\n')) log(`[${tag}] ${l}`);
}

// ---------------- device description fetch ----------------
function fetchDescription(usn, location) {
  if (devices.has(usn)) return;
  http.get(location, (res) => {
    let body = '';
    res.on('data', (c) => (body += c));
    res.on('end', () => {
      const name = (body.match(/<friendlyName>\s*(.*?)\s*<\/friendlyName>/) || [])[1] || '?';
      const devType = (body.match(/<deviceType>\s*(.*?)\s*<\/deviceType>/) || [])[1] || '?';
      const services = [...new Set([...body.matchAll(/<serviceType>\s*(.*?)\s*<\/serviceType>/g)].map(m => m[1]))];
      devices.set(usn, { name, location, devType, services });
      log(`=== DEVICE FOUND: usn=${usn} name="${name}" type=${devType} loc=${location}`);
      log(`===   services: ${services.join(', ')}`);
      log(`===   description XML:\n${body}`);
    });
  }).on('error', (e) => log(`[desc] fetch failed ${location}: ${e.message}`));
}
function handleAnnounce(msg) {
  const loc = (msg.match(/LOCATION:\s*(\S+)/i) || [])[1];
  if (!loc) return;
  const usn = (msg.match(/USN:\s*(\S+)/i) || [])[1] || loc;
  fetchDescription(usn, loc);
}

// ---------------- SSDP multicast listener ----------------
function startMulticastListener() {
  const sock = dgram.createSocket({ type: 'udp4', reuseAddr: true });
  sock.on('error', (e) => { log(`[ssdp] error: ${e.message}`); setTimeout(startMulticastListener, 3000); });
  sock.on('message', (buf, rinfo) => {
    const msg = buf.toString('utf8');
    const first = msg.split('\r')[0];
    if (first.startsWith('M-SEARCH')) {
      logPacket('M-SEARCH', rinfo, msg);
      const st = (msg.match(/ST:\s*(.+?)\s*\r?\n/i) || [])[1];
      if (st) answerSearch(sock, rinfo, st.trim());
    } else if (first.startsWith('NOTIFY')) {
      logPacket('NOTIFY', rinfo, msg);
      handleAnnounce(msg);
    } else {
      logPacket('SSDP-OTHER', rinfo, msg);
      if (/LOCATION:/i.test(msg)) handleAnnounce(msg);
    }
  });
  sock.bind(SSDP_PORT, () => {
    sock.addMembership(SSDP_ADDR);
    log(`[ssdp] multicast listener ready on ${SSDP_PORT}`);
  });
}

function sendUdp(sock, buf, addr, port) { sock.send(buf, 0, buf.length, port, addr); }

function answerSearch(sock, to, st) {
  let entries;
  if (st === 'ssdp:all' || st === 'upnp:rootdevice') {
    entries = ['upnp:rootdevice', UDN, 'urn:schemas-upnp-org:device:MediaRenderer:1',
      'urn:schemas-upnp-org:service:AVTransport:1', 'urn:schemas-tencent-com:service:QPlay:1'];
  } else if (st.includes('MediaRenderer') || /qplay/i.test(st)) {
    entries = [st];
  } else return;
  for (const nt of entries) {
    const resp =
      'HTTP/1.1 200 OK\r\n' +
      'CACHE-CONTROL: max-age=1800\r\n' +
      'EXT:\r\n' +
      'SERVER: QQMusicWear/1.0 UPnP/1.0 QPlayProbe/0.1\r\n' +
      `LOCATION: http://${LOCAL_IP}:${HTTP_PORT}/description.xml\r\n` +
      `ST: ${nt}\r\n` +
      `USN: ${UDN}::${nt}\r\n` +
      'BOOTID.UPNP.ORG: 1\r\n' +
      'CONFIGID.UPNP.ORG: 1\r\n\r\n';
    sendUdp(sock, Buffer.from(resp), to.address, to.port);
  }
  log(`[ssdp] answered M-SEARCH ST=${st} to ${to.address}:${to.port}`);
}

// ---------------- NOTIFY alive advertiser ----------------
function startAdvertiser() {
  const sock = dgram.createSocket('udp4');
  setInterval(() => {
    for (const nt of ['upnp:rootdevice', UDN, 'urn:schemas-upnp-org:device:MediaRenderer:1',
      'urn:schemas-upnp-org:service:AVTransport:1', 'urn:schemas-tencent-com:service:QPlay:1',
      'urn:schemas-upnp-org:service:ConnectionManager:1', 'urn:schemas-upnp-org:service:RenderingControl:1']) {
      const pkt =
        'NOTIFY * HTTP/1.1\r\n' +
        'HOST: 239.255.255.250:1900\r\n' +
        'CACHE-CONTROL: max-age=1800\r\n' +
        `LOCATION: http://${LOCAL_IP}:${HTTP_PORT}/description.xml\r\n` +
        `NT: ${nt}\r\n` +
        'NTS: ssdp:alive\r\n' +
        `USN: ${UDN}::${nt}\r\n` +
        'SERVER: QQMusicWear/1.0 UPnP/1.0 QPlayProbe/0.1\r\n' +
        'BOOTID.UPNP.ORG: 1\r\n' +
        'CONFIGID.UPNP.ORG: 1\r\n\r\n';
      sendUdp(sock, Buffer.from(pkt), SSDP_ADDR, SSDP_PORT);
    }
  }, 15000);
}

// ---------------- active M-SEARCH scanner ----------------
function startScanner() {
  const scan = () => {
    const sock = dgram.createSocket('udp4');
    sock.on('message', (buf, rinfo) => {
      const msg = buf.toString('utf8');
      logPacket('SEARCH-RESP', rinfo, msg);
      handleAnnounce(msg);
    });
    sock.on('error', () => {});
    sock.bind(() => {
      const sts = ['ssdp:all', 'upnp:rootdevice', 'urn:schemas-upnp-org:device:MediaRenderer:1'];
      let i = 0;
      const iv = setInterval(() => {
        if (i >= sts.length) { clearInterval(iv); setTimeout(() => sock.close(), 5000); return; }
        const pkt =
          'M-SEARCH * HTTP/1.1\r\n' +
          'HOST: 239.255.255.250:1900\r\n' +
          'MAN: "ssdp:discover"\r\n' +
          'MX: 3\r\n' +
          `ST: ${sts[i++]}\r\n\r\n`;
        sendUdp(sock, Buffer.from(pkt), SSDP_ADDR, SSDP_PORT);
      }, 300);
    });
  };
  setInterval(scan, 8000);
  scan();
}

// ---------------- HTTP / SOAP server ----------------
const server = http.createServer((req, res) => {
  let body = '';
  req.on('data', (c) => (body += c));
  req.on('end', () => {
    log(`======== HTTP-IN ${req.method} ${req.url} from ${req.socket.remoteAddress} ========`);
    for (const [k, v] of Object.entries(req.headers)) log(`[http-h] ${k}: ${v}`);
    if (body) log(`[http-body]\n${body.replace(/></g, '>\n<')}`);

    if (req.method === 'SUBSCRIBE' || req.method === 'UNSUBSCRIBE') {
      const sid = crypto.randomUUID();
      const service = req.url.includes('AVTransport') ? 'AVTransport' : 'RenderingControl';
      if (req.method === 'SUBSCRIBE') {
        const cb = (req.headers.callback || '').match(/<([^>]+)>/);
        if (cb) callbacks[service].push({ url: cb[1], sid: `uuid:${sid}` });
        log(`[gena] SUBSCRIBE ${service} callback=${cb ? cb[1] : '?'} sid=${sid}`);
        // 规范要求订阅成功后立即回发初始状态事件 (SEQ=0)
        sendNotify(service, `uuid:${sid}`);
      } else {
        callbacks[service] = callbacks[service].filter((c) => c.sid !== req.headers.sid);
        log(`[gena] UNSUBSCRIBE ${service} sid=${req.headers.sid}`);
      }
      res.writeHead(200, { SERVER: 'QQMusicWear/1.0 UPnP/1.0', SID: `uuid:${sid}`, TIMEOUT: 'Second-1800', 'CONTENT-LENGTH': '0' });
      res.end();
      return;
    }

    let respBody = '';
    if (req.url.startsWith('/description.xml')) respBody = deviceDescription();
    else if (req.url.startsWith('/scpd/')) respBody = scpd(req.url);
    else if (req.url.startsWith('/icon.png')) {
      const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==', 'base64');
      res.writeHead(200, { 'Content-Type': 'image/png', 'Content-Length': png.length });
      res.end(png);
      return;
    } else respBody = soapResponse(req.headers, body);

    const b = Buffer.from(respBody, 'utf8');
    res.writeHead(200, {
      SERVER: 'QQMusicWear/1.0 UPnP/1.0',
      'CONTENT-TYPE': 'text/xml; charset="utf-8"',
      'CONTENT-LENGTH': b.length,
      CONNECTION: 'close',
    });
    res.end(b);
  });
});

function md5Hex(s) { return crypto.createHash('md5').update(s || '', 'utf8').digest('hex'); }

function unescapeXml(s) {
  return String(s).replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, '&');
}
function xmlEscape(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&apos;');
}
function fmtElapsed() {
  const s = Math.max(0, Math.floor((Date.now() - playStartTs) / 1000));
  return `${Math.floor(s / 3600)}:${String(Math.floor((s % 3600) / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;
}

// GENA 事件回发: 告诉控制点播放状态已变化 (手机等待的就是这个)
function avtLastChange() {
  const t = queue[curIndex];
  return `<Event xmlns="urn:schemas-upnp-org:metadata-1-0/AVT/"><InstanceID val="0">` +
    `<TransportState val="${transportState}"/>` +
    `<NumberOfTracks val="${queue.length}"/>` +
    `<CurrentTrack val="${curIndex + 1}"/>` +
    `<CurrentTrackDuration val="${t ? t.duration : '0:00:00'}"/>` +
    `<AVTransportURI val="${xmlEscape('qplay://' + queueId)}"/>` +
    `<CurrentTrackURI val="${xmlEscape(t && t.trackURIs && t.trackURIs[0] || '')}"/>` +
    `</InstanceID></Event>`;
}
function sendNotify(service, onlySid) {
  const list = callbacks[service] || [];
  if (!list.length) return;
  const last = service === 'AVTransport'
    ? avtLastChange()
    : '<Event xmlns="urn:schemas-upnp-org:metadata-1-0/RCS/"><InstanceID val="0"><Volume channel="Master" val="30"/><Mute channel="Master" val="0"/></InstanceID></Event>';
  const body = `<?xml version="1.0" encoding="utf-8"?>\n<e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0"><e:property><LastChange>${xmlEscape(last)}</LastChange></e:property></e:propertyset>`;
  for (const cb of list) {
    if (onlySid && cb.sid !== onlySid) continue;
    const seq = seqMap.get(cb.sid) || 0;
    try {
      const u = new URL(cb.url);
      const rq = http.request({
        host: u.hostname, port: u.port || 80, path: u.pathname + (u.search || ''), method: 'NOTIFY',
        headers: { HOST: `${u.hostname}:${u.port || 80}`, 'CONTENT-TYPE': 'text/xml; charset="utf-8"', NT: 'upnp:event', NTS: 'upnp:propchange', SID: cb.sid, SEQ: String(seq), 'CONTENT-LENGTH': Buffer.byteLength(body) },
      }, (res) => res.resume());
      rq.on('error', (e) => log(`[gena] notify error: ${e.message}`));
      rq.end(body);
      seqMap.set(cb.sid, seq + 1);
      log(`[gena] NOTIFY -> ${cb.url} seq=${seq} state=${transportState}`);
      log(`[gena]   LastChange=${last}`);
    } catch (e) { log(`[gena] notify fail: ${e.message}`); }
  }
}

// 实际播放: 下载 MP3 到本地缓存 → Edge 独立实例播放 (WMP COM 在本机不可用)
const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const edgeProfileDir = path.join(__dirname, 'edge_profile');
const cacheDir = path.join(__dirname, 'cache');
let edgeProc = null;
let downloadReq = null;
try { fs.mkdirSync(cacheDir, { recursive: true }); } catch (e) {}

function killEdge() {
  if (downloadReq) { try { downloadReq.destroy(); } catch (e) {} downloadReq = null; }
  if (edgeProc) {
    try { spawn('taskkill', ['/PID', String(edgeProc.pid), '/T', '/F'], { stdio: 'ignore' }); } catch (e) {}
    edgeProc = null;
  }
}

function launchEdge(file) {
  const url = 'file:///' + file.replace(/\\/g, '/');
  try {
    edgeProc = spawn(EDGE, [
      '--autoplay-policy=no-user-gesture-required',
      `--user-data-dir=${edgeProfileDir}`,
      '--window-size=420,160',
      `--app=${url}`,
    ], { stdio: 'ignore' });
    log('[player] Edge playback window opened (sound should come out of the PC)');
  } catch (e) { log(`[player] Edge spawn fail: ${e.message}`); }
}

function startPlayback(track) {
  killEdge();
  if (!track || !track.trackURIs || !track.trackURIs[0]) return;
  const uri = track.trackURIs[0];
  const file = path.join(cacheDir, `${track.songID || Date.now()}.mp3`);
  log(`[player] play "${track.title}" - ${track.creator} (songID=${track.songID})`);
  log(`[player]   uri=${uri}`);
  try {
    const rq = http.get(uri, { headers: { 'User-Agent': 'QQMusicWearProbe/1.0' } }, (res) => {
      if (res.statusCode !== 200 && res.statusCode !== 206) {
        log(`[player] download fail: status=${res.statusCode}`);
        return;
      }
      const ws = fs.createWriteStream(file);
      res.pipe(ws);
      ws.on('finish', () => {
        log(`[player] download complete (${file}), starting playback`);
        launchEdge(file);
      });
      ws.on('error', (e) => log(`[player] write fail: ${e.message}`));
    });
    rq.on('error', (e) => log(`[player] download error: ${e.message}`));
    downloadReq = rq;
  } catch (e) { log(`[player] download fail: ${e.message}`); }
}

function soapResponse(headers, body) {
  const action = (headers.soapaction || '').replace(/"/g, '');
  const [svc, act] = action.includes('#') ? action.split('#') : ['urn:schemas-upnp-org:service:AVTransport:1', 'Unknown'];
  log(`>>> SOAP ACTION: service=${svc} action=${act}`);

  if (act === 'QPlayAuth') {
    const seed = (body.match(/<Seed>(.*?)<\/Seed>/) || [])[1] || '';
    log(`>>> QPlayAuth requested! seed=${seed} (无 PSK, 返回占位 Code)`);
    return envelope(`<u:QPlayAuthResponse xmlns:u="${svc}">
<MID>QMW</MID><DID>WATCH</DID><Code>${md5Hex(seed)}</Code>
</u:QPlayAuthResponse>`);
  }

  if (act === 'SetTracksInfo') {
    try {
      const raw = (body.match(/<TracksMetaData>([\s\S]*?)<\/TracksMetaData>/) || [])[1] || '';
      const meta = JSON.parse(unescapeXml(raw));
      queue = meta.TracksMetaData || [];
      const qid = (body.match(/<QueueID>(.*?)<\/QueueID>/) || [])[1];
      if (qid) queueId = qid;
      curIndex = 0;
      log(`>>> 队列已加载: QueueID=${queueId} 共${queue.length}首, 第1首: ${queue[0] ? queue[0].title : '?'}`);
      return envelope(`<u:SetTracksInfoResponse xmlns:u="${svc}"><NumberOfSuccess>${queue.length}</NumberOfSuccess></u:SetTracksInfoResponse>`);
    } catch (e) {
      log(`>>> SetTracksInfo parse fail: ${e.message}`);
      return envelope(`<u:SetTracksInfoResponse xmlns:u="${svc}"><NumberOfSuccess>0</NumberOfSuccess></u:SetTracksInfoResponse>`);
    }
  }
  if (act === 'SetAVTransportURI') {
    const uri = (body.match(/<CurrentURI>([\s\S]*?)<\/CurrentURI>/) || [])[1] || '';
    log(`>>> SetAVTransportURI: ${uri}`);
    if (uri.startsWith('qplay://')) queueId = uri.slice(8);
    transportState = 'STOPPED';
    return envelope(`<u:SetAVTransportURIResponse xmlns:u="${svc}"></u:SetAVTransportURIResponse>`);
  }
  if (act === 'Seek') {
    const unit = (body.match(/<Unit>(.*?)<\/Unit>/) || [])[1] || '';
    const target = (body.match(/<Target>(.*?)<\/Target>/) || [])[1] || '0';
    log(`>>> Seek ${unit} -> ${target}`);
    if (unit === 'TRACK_NR') curIndex = Math.max(0, parseInt(target, 10) - 1);
    sendNotify('AVTransport');
    return envelope(`<u:SeekResponse xmlns:u="${svc}"></u:SeekResponse>`);
  }
  if (act === 'Play') {
    if (transportState === 'PLAYING') {
      // 已在播放: 只回发事件确认, 不重置状态 (手机等不到事件时会连发 Play)
      sendNotify('AVTransport');
    } else {
      transportState = 'TRANSITIONING';
      sendNotify('AVTransport');
      const token = ++playToken;
      setTimeout(() => {
        if (token !== playToken) return; // 期间收到 Stop/Pause 则跳过
        transportState = 'PLAYING';
        playStartTs = Date.now();
        log(`>>> Play: 第 ${curIndex + 1} 首: ${queue[curIndex] ? queue[curIndex].title : '(队列空)'}`);
        startPlayback(queue[curIndex]);
        sendNotify('AVTransport');
      }, 300);
    }
    return envelope(`<u:PlayResponse xmlns:u="${svc}"></u:PlayResponse>`);
  }
  if (act === 'Pause') {
    transportState = 'PAUSED_PLAYBACK';
    killEdge();
    log('>>> Pause');
    sendNotify('AVTransport');
    return envelope(`<u:PauseResponse xmlns:u="${svc}"></u:PauseResponse>`);
  }
  if (act === 'Stop') {
    transportState = 'STOPPED';
    killEdge();
    log('>>> Stop');
    sendNotify('AVTransport');
    return envelope(`<u:StopResponse xmlns:u="${svc}"></u:StopResponse>`);
  }
  if (act === 'SetPlayMode') {
    const mode = (body.match(/<NewPlayMode>(.*?)<\/NewPlayMode>/) || [])[1] || '';
    log(`>>> SetPlayMode: ${mode}`);
    return envelope(`<u:SetPlayModeResponse xmlns:u="${svc}"></u:SetPlayModeResponse>`);
  }
  if (act === 'GetTracksInfo') {
    const meta = JSON.stringify({ TracksMetaData: queue });
    return envelope(`<u:GetTracksInfoResponse xmlns:u="${svc}"><StartingIndex>0</StartingIndex><TracksMetaData>${xmlEscape(meta)}</TracksMetaData></u:GetTracksInfoResponse>`);
  }
  if (act === 'GetTracksCount')
    return envelope(`<u:GetTracksCountResponse xmlns:u="${svc}"><NrTracks>${queue.length}</NrTracks></u:GetTracksCountResponse>`);
  if (act === 'GetMaxTracks')
    return envelope(`<u:GetMaxTracksResponse xmlns:u="${svc}"><MaxTracks>200</MaxTracks></u:GetMaxTracksResponse>`);

  let state = '';
  if (act === 'GetTransportInfo')
    state = `<CurrentTransportState>${transportState}</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>`;
  if (act === 'GetPositionInfo') {
    const t = queue[curIndex];
    state = `<Track>${curIndex + 1}</Track><TrackDuration>${t ? t.duration : '0:00:00'}</TrackDuration><TrackMetaData></TrackMetaData><TrackURI>${xmlEscape(t && t.trackURIs && t.trackURIs[0] || '')}</TrackURI><RelTime>${transportState === 'PLAYING' ? fmtElapsed() : '0:00:00'}</RelTime><AbsTime>0:00:00</AbsTime><RelCount>2147483647</RelCount><AbsCount>2147483647</AbsCount>`;
  }
  if (act === 'GetMediaInfo') {
    const t = queue[curIndex];
    state = `<NrTracks>${queue.length}</NrTracks><MediaDuration>${t ? t.duration : '0:00:00'}</MediaDuration><CurrentURI>${xmlEscape('qplay://' + queueId)}</CurrentURI><CurrentURIMetaData></CurrentURIMetaData><NextURI></NextURI><NextURIMetaData></NextURIMetaData><PlayMedium>NETWORK</PlayMedium><RecordMedium>NOT_IMPLEMENTED</RecordMedium><WriteStatus>NOT_IMPLEMENTED</WriteStatus>`;
  }
  if (act === 'GetMute') state = '<CurrentMute>0</CurrentMute>';
  if (act === 'GetVolume') state = '<CurrentVolume>30</CurrentVolume>';

  return envelope(`<u:${act}Response xmlns:u="${svc}">
${state}
</u:${act}Response>`);
}
function envelope(inner) {
  return `<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
${inner}
</s:Body>
</s:Envelope>`;
}

// 与真实 QQ 音乐 PC 端 (TxMediaRenderer_desc.xml) 完全一致的设备描述
function deviceDescription() {
  return `<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:qq="http://www.tencent.com">
<specVersion><major>1</major><minor>0</minor></specVersion>
<device>
<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
<friendlyName>${FRIENDLY_NAME}</friendlyName>
<manufacturer>Tencent</manufacturer>
<manufacturerURL>https://y.qq.com</manufacturerURL>
<modelDescription>Media Renderer Device</modelDescription>
<modelName>QQMusicWear</modelName>
<modelNumber>1.0</modelNumber>
<UDN>${UDN}</UDN>
<serviceList>
<service>
<serviceType>urn:schemas-tencent-com:service:QPlay:1</serviceType>
<serviceId>urn:tencent-com:serviceId:QPlay</serviceId>
<SCPDURL>_urn-schemas-upnp-org-service-QPlay_scpd.xml</SCPDURL>
<controlURL>_urn-schemas-upnp-org-service-QPlay_control</controlURL>
<eventSubURL>_urn-schemas-upnp-org-service-QPlay_event</eventSubURL>
</service>
<service>
<serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
<serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
<SCPDURL>_urn-schemas-upnp-org-service-AVTransport_scpd.xml</SCPDURL>
<controlURL>_urn-schemas-upnp-org-service-AVTransport_control</controlURL>
<eventSubURL>_urn-schemas-upnp-org-service-AVTransport_event</eventSubURL>
</service>
<service>
<serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
<serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
<SCPDURL>_urn-schemas-upnp-org-service-ConnectionManager_scpd.xml</SCPDURL>
<controlURL>_urn-schemas-upnp-org-service-ConnectionManager_control</controlURL>
<eventSubURL>_urn-schemas-upnp-org-service-ConnectionManager_event</eventSubURL>
</service>
<service>
<serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
<serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
<SCPDURL>_urn-schemas-upnp-org-service-RenderingControl_scpd.xml</SCPDURL>
<controlURL>_urn-schemas-upnp-org-service-RenderingControl_control</controlURL>
<eventSubURL>_urn-schemas-upnp-org-service-RenderingControl_event</eventSubURL>
</service>
</serviceList>
<modelURL>https://y.qq.com</modelURL>
<qq:AppVersion>22.61</qq:AppVersion>
<qq:MiniVersion>2.1</qq:MiniVersion>
<qq:PlatformOS>android</qq:PlatformOS>
<qq:X_QPlay_SoftwareCapability>QPlay:2</qq:X_QPlay_SoftwareCapability>
</device>
</root>`;
}

// SCPD 直接回放真实 QQ 音乐 PC 端抓取的文件
function scpd(urlPath) {
  const captured = path.join(__dirname, 'captured');
  const real = path.join(captured, urlPath.replace(/^\/+/, ''));
  try {
    if (fs.existsSync(real)) return fs.readFileSync(real, 'utf8');
  } catch (e) { /* fallthrough */ }
  const actions = ['GetProtocolInfo', 'GetCurrentConnectionIDs', 'GetCurrentConnectionInfo'];
  return `<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
<specVersion><major>1</major><minor>0</minor></specVersion>
<actionList>
${actions.map((a) => `<action><name>${a}</name></action>`).join('\n')}
</actionList>
<serviceStateTable>
<stateVariable sendEvents="yes"><name>TransportState</name><dataType>string</dataType></stateVariable>
</serviceStateTable>
</scpd>`;
}

// ---------------- main ----------------
log(`=== QPlayProbe started, localIp=${LOCAL_IP}, httpPort=${HTTP_PORT} ===`);
startMulticastListener();
startAdvertiser();
startScanner();
server.listen(HTTP_PORT, () => log(`[http] server listening on ${LOCAL_IP}:${HTTP_PORT}`));

setInterval(() => {
  if (devices.size === 0) { log('[summary] no devices discovered yet'); return; }
  log(`[summary] ${devices.size} device(s):`);
  for (const [usn, d] of devices) log(`[summary]   ${d.name} | ${d.devType} | ${d.location}`);
}, 20000);
