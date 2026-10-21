# -*- coding: utf-8 -*-
"""
番茄畅听取链中转服务（本地版 v2）
==================================
手表源 --HTTP--> 本服务 --Frida RPC--> 真机番茄畅听 App --> 直链

端点：
  GET  /health    健康检查
  POST /resolve   {"book_id":"..","item_ids":[".."]} -> {"item_id","expire","duration","urls":[{quality,size,url}]}
  POST /raw       原始 video_model JSON（调试）

机制：
  - hook VideoModelData.$init 捕获响应（itemId -> video_model JSON）
  - hook PlayerApiService 全方法捕获代理实例；resolve 时直接 svc.j(req)（字节 retrofit invoke 即发请求，无需 subscribe）
  - RPC 立即返回；Python 侧轮询 pending（不阻塞 Frida 消息泵）
"""
import frida
import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DEVICE_ID = 'bipnlzaa6xy9nbee'
PORT = 8791
ADB_DIR = r'C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools'
os.environ['PATH'] = ADB_DIR + os.pathsep + os.environ.get('PATH', '')

SCRIPT_JS = r'''
'use strict';
var pending = {};          // itemId -> video_model JSON 字符串
var globalSvc = null;      // PlayerApiService 代理实例
var svcWaiters = [];
var globalBook = null;     // BookApiService 代理实例
var dirLast = '';          // 最近一次目录响应 JSON（Gson 序列化）

Java.perform(function () {
    send('perform_ok');

    // 1. 捕获取链响应
    var VMD = Java.use('com.xs.fm.rpc.model.VideoModelData');
    VMD.$init.overloads.forEach(function (ov) {
        ov.implementation = function () {
            var r = ov.apply(this, arguments);
            try {
                var cls = this.getClass();
                var f1 = cls.getDeclaredField('itemId'); f1.setAccessible(true);
                var f2 = cls.getDeclaredField('videoModel'); f2.setAccessible(true);
                var vm = f2.get(this);
                var vmJson = '';
                if (vm !== null) {
                    try {
                        var fvm = vm.getClass().getDeclaredField('video_model');
                        fvm.setAccessible(true);
                        vmJson = '' + fvm.get(vm);
                    } catch (e) { vmJson = '' + vm; }
                }
                pending['' + f1.get(this)] = vmJson;
            } catch (e) { send('CAPTURE_ERR ' + e); }
            return r;
        };
    });
    send('capture hooked');

    // 2. 被动捕获 PlayerApiService 实例
    var P = Java.use('com.xs.fm.rpc.rpc.PlayerApiService');
    var ms = P.class.getDeclaredMethods();
    for (var mi = 0; mi < ms.length; mi++) {
        (function (mn) {
            try {
                P[mn].overloads.forEach(function (ov) {
                    ov.implementation = function () {
                        if (globalSvc === null) { send('SVC_CAPTURED via ' + mn); globalSvc = this; }
                        return ov.apply(this, arguments);
                    };
                });
            } catch (e) {}
        })(ms[mi].getName());
    }
    send('passive capture hooked');

    var ReqC = Java.use('com.xs.fm.rpc.model.MGetVideoModelRequest');
    var EnumAudio = null, EnumBGM = null;
    try { EnumAudio = Java.use('com.xs.fm.rpc.model.AudioPlayerType'); } catch (e) {}
    try { EnumBGM = Java.use('com.xs.fm.rpc.model.BgmPlayerType'); } catch (e) {}

    function findEnum(cls, name) {
        if (!cls) return null;
        try {
            var vals = cls.values();
            for (var i = 0; i < vals.length; i++) {
                if (('' + vals[i].name()) === name) return vals[i];
            }
        } catch (e) {}
        return null;
    }

    function setField(obj, name, val) {
        var cls = obj.getClass();
        while (cls) {
            try {
                var f = cls.getDeclaredField(name);
                f.setAccessible(true);
                f.set(obj, val);
                return true;
            } catch (e) {}
            cls = cls.getSuperclass();
        }
        return false;
    }

    // rxjava2 Consumer 实现（订阅用）
    var NoopConsumer = null;
    var ErrConsumer = null;
    try {
        var ConsumerIntf = Java.use('io.reactivex.functions.Consumer');
        NoopConsumer = Java.registerClass({
            name: 'com.fq.proxy.NoopConsumer',
            implements: [ConsumerIntf],
            methods: {
                accept: function (t) { }
            }
        });
        ErrConsumer = Java.registerClass({
            name: 'com.fq.proxy.ErrConsumer',
            implements: [ConsumerIntf],
            methods: {
                accept: function (t) { send('CALL_ERR ' + t); }
            }
        });
        send('NoopConsumer registered');
    } catch (e) { send('registerClass ERR ' + e); }

    // ===== 目录支持 =====
    var Gson = null;
    try { Gson = Java.use('com.google.gson.Gson').$new(); } catch (e) { send('gson ERR ' + e); }
    var lastNaturalDirReq = null;  // App 自然调 h() 时的请求对象（用于克隆复刻）
    var lastNaturalInfoReq = null; // App 自然调 i() 时的请求对象
    var dirReqJson = '';           // 自然请求的 Gson JSON（调试用）
    var infoList = [];             // GetDirectoryItemInfoResponse JSON 列表
    // 被动捕获 BookApiService 实例
    var BB = Java.use('com.xs.fm.rpc.rpc.BookApiService');
    var bms = BB.class.getDeclaredMethods();
    for (var bi = 0; bi < bms.length; bi++) {
        (function (mn) {
            try {
                BB[mn].overloads.forEach(function (ov) {
                    ov.implementation = function () {
                        if (globalBook === null) { send('BOOK_CAPTURED via ' + mn); globalBook = this; }
                        try {
                            if (Gson !== null) {
                                var parts = [];
                                for (var ai = 0; ai < arguments.length; ai++) {
                                    try { parts.push(Gson.toJson(arguments[ai])); }
                                    catch (e) { parts.push('' + arguments[ai]); }
                                }
                                send('BOOK_CALL ' + mn + ' args=' + parts.join(' | ').substring(0, 600));
                            }
                        } catch (e) {}
                        if (arguments.length > 0 && arguments[0] !== null) {
                            try {
                                var an = arguments[0].getClass().getName();
                                if (an === 'com.xs.fm.rpc.model.GetDirectoryItemIdsRequest') {
                                    lastNaturalDirReq = Java.retain(arguments[0]);
                                    if (Gson !== null) {
                                        var rj = Gson.toJson(arguments[0]);
                                        if (rj && rj.length > 2) { dirReqJson = rj; send('DIR_REQ via ' + mn + ' ' + rj.substring(0, 500)); }
                                    }
                                } else if (an === 'com.xs.fm.rpc.model.GetDirectoryItemInfoRequest') {
                                    lastNaturalInfoReq = Java.retain(arguments[0]);
                                    send('INFO_REQ_RETAINED via ' + mn);
                                }
                            } catch (e) {}
                        }
                        return ov.apply(this, arguments);
                    };
                });
            } catch (e) {}
        })(bms[bi].getName());
    }
    // 响应捕获（Gson 序列化，避免反射遍历崩溃）
    try {
        var DirResp = Java.use('com.xs.fm.rpc.model.GetDirectoryItemIdsResponse');
        DirResp.$init.overloads.forEach(function (ov) {
            ov.implementation = function () {
                var r = ov.apply(this, arguments);
                try {
                    if (Gson !== null) {
                        var j = Gson.toJson(this);
                        if (j && j.length > 100) {
                            dirLast = j;
                            send('DIR_JSON len=' + j.length);
                        }
                    }
                } catch (e) { send('DIR_SER_ERR ' + e); }
                return r;
            };
        });
        send('dir resp hooked');
    } catch (e) { send('dir hook ERR ' + e); }

    // 批量 item 详情响应捕获（i 方法的响应）
    try {
        var InfoResp = Java.use('com.xs.fm.rpc.model.GetDirectoryItemInfoResponse');
        InfoResp.$init.overloads.forEach(function (ov) {
            ov.implementation = function () {
                var r = ov.apply(this, arguments);
                try {
                    if (Gson !== null) {
                        var j = Gson.toJson(this);
                        if (j && j.length > 50 && j.indexOf('item_id') >= 0) {
                            infoList.push(j);
                            send('INFO_JSON len=' + j.length + ' total=' + infoList.length);
                        }
                    }
                } catch (e) { send('INFO_SER_ERR ' + e); }
                return r;
            };
        });
        send('info resp hooked');
    } catch (e) { send('info hook ERR ' + e); }

    var DirReqC = Java.use('com.xs.fm.rpc.model.GetDirectoryItemIdsRequest');

    function cloneReq(src, bookId) {
        var req = DirReqC.$new();
        if (src !== null) {
            var cls = src.getClass();
            while (cls) {
                try {
                    var fs = cls.getDeclaredFields();
                    for (var i = 0; i < fs.length; i++) {
                        try {
                            fs[i].setAccessible(true);
                            fs[i].set(req, fs[i].get(src));
                        } catch (e) {}
                    }
                } catch (e) {}
                cls = cls.getSuperclass();
            }
        }
        setField(req, 'bookId', '' + bookId);
        setField(req, 'topBookId', '' + bookId);
        return req;
    }

    rpc.exports = {
        fire: function (bookId, itemId) {
            try {
                if (globalSvc === null) return JSON.stringify({ err: 'svc_not_captured' });
                var req = ReqC.$new();
                setField(req, 'audioType', findEnum(EnumAudio, 'BOOK'));
                setField(req, 'bGMUsed', findEnum(EnumBGM, 'Original'));
                setField(req, 'bookId', '' + bookId);
                setField(req, 'deviceScore', 0.0);
                setField(req, 'isRetry', false);
                var AL = Java.use('java.util.ArrayList');
                var ids = AL.$new();
                ids.add('' + itemId);
                setField(req, 'itemIds', ids);
                setField(req, 'multiShift', false);
                setField(req, 'needCategoryName', true);
                setField(req, 'source', 'audio_continuous_preload_more');
                setField(req, 'toneId', 0);
                setField(req, 'userSelectStartPara', 0);
                setField(req, 'userSelectStartParaOff', 0);
                delete pending['' + itemId];
                var ret = globalSvc.j(req);
                var retCls = ret === null ? 'null' : '' + ret.getClass().getName();
                if (NoopConsumer !== null && ret !== null) {
                    try { ret.subscribe(NoopConsumer.$new()); }
                    catch (se) { send('SUB_ERR ' + se); }
                }
                send('FIRED ' + itemId + ' ret=' + retCls);
                return JSON.stringify({ ok: true, ret: retCls });
            } catch (e) {
                return JSON.stringify({ err: '' + e });
            }
        },
        check: function (itemId) {
            var v = pending['' + itemId];
            return v === undefined ? JSON.stringify({ waiting: true }) : JSON.stringify({ ok: true, json: v });
        },
        // 主动触发目录请求（需 globalBook 已捕获；优先克隆 App 自然请求复刻字段）
        firedir: function (bookId) {
            try {
                if (globalBook === null) return JSON.stringify({ err: 'book_not_captured' });
                dirLast = '';
                var req;
                if (lastNaturalDirReq !== null) {
                    req = cloneReq(lastNaturalDirReq, bookId);
                    send('DIR_CLONED_FROM_NATURAL');
                } else {
                    req = DirReqC.$new();
                    setField(req, 'bookId', '' + bookId);
                    send('DIR_REQ_BARE');
                }
                var ret = globalBook.h(req);
                ret.subscribe(NoopConsumer.$new(), ErrConsumer.$new());
                send('DIR_FIRED ' + bookId);
                return JSON.stringify({ ok: true });
            } catch (e) { return JSON.stringify({ err: '' + e }); }
        },
        dirjson: function () {
            return dirLast === '' ? JSON.stringify({ waiting: true }) : dirLast;
        },
        dirreq: function () {
            return dirReqJson === '' ? JSON.stringify({ waiting: true }) : dirReqJson;
        },
        // 批量 item 详情（i 方法）：idsJson 为字符串数组 JSON
        fireinfo: function (bookId, idsJson) {
            try {
                if (globalBook === null) return JSON.stringify({ err: 'book_not_captured' });
                var ids = JSON.parse(idsJson);
                var InfoReqC = Java.use('com.xs.fm.rpc.model.GetDirectoryItemInfoRequest');
                var req = InfoReqC.$new();
                setField(req, 'bookId', '' + bookId);
                var AL = Java.use('java.util.ArrayList');
                var lst = AL.$new();
                for (var i = 0; i < ids.length; i++) lst.add('' + ids[i]);
                setField(req, 'itemIds', lst);
                setField(req, 'hasNeedParaItems', false);
                try {
                    if (Gson !== null) send('INFO_REQ_JSON ' + Gson.toJson(req).substring(0, 300));
                } catch (e) { send('INFO_REQ_GSON_ERR ' + e); }
                var ret = globalBook.i(req);
                ret.subscribe(NoopConsumer.$new(), ErrConsumer.$new());
                send('INFO_FIRED n=' + ids.length);
                return JSON.stringify({ ok: true });
            } catch (e) { return JSON.stringify({ err: '' + e }); }
        },
        infojson: function () {
            return JSON.stringify({ items: infoList });
        },
        clearinfo: function () {
            infoList = [];
            return 'ok';
        },
        // dump rpc 服务方法签名（参数类型 + 泛型返回类型）
        dumpsvc: function () {
            try {
                var out = [];
                var names = ['com.xs.fm.rpc.rpc.BookApiService', 'com.xs.fm.rpc.rpc.PlayerApiService'];
                for (var ni = 0; ni < names.length; ni++) {
                    var C = Java.use(names[ni]);
                    var ms = C.class.getDeclaredMethods();
                    for (var i = 0; i < ms.length; i++) {
                        try {
                            var m = ms[i];
                            var ptypes = m.getParameterTypes();
                            var ps = [];
                            for (var pi = 0; pi < ptypes.length; pi++) ps.push('' + ptypes[pi].getName());
                            out.push(names[ni].split('.').pop() + '.' + m.getName() + '(' + ps.join(',') + ') -> ' + m.getGenericReturnType().toString());
                        } catch (e) {}
                    }
                }
                return out.join('\n');
            } catch (e) { return 'ERR ' + e; }
        },
        ping: function () { return 'pong'; }
    };

    send('rpc ready');
});
'''

_state = {'script': None, 'session': None, 'lock': threading.Lock()}


def on_message(message, data):
    if message['type'] == 'send':
        print('[app]', message['payload'], flush=True)
    elif message['type'] == 'error':
        print('[ERR]', message.get('stack') or message.get('description'), flush=True)


def ensure_session():
    with _state['lock']:
        if _state['script'] is not None:
            try:
                _state['script'].exports_sync.ping()
                return _state['script']
            except Exception:
                try:
                    _state['session'].detach()
                except Exception:
                    pass
                _state['script'] = None
                _state['session'] = None
        import subprocess
        pid = int(subprocess.run(['adb', '-s', DEVICE_ID, 'shell', 'pidof', 'com.xs.fm'],
                                 capture_output=True, text=True, timeout=10).stdout.strip())
        dev = frida.get_device(DEVICE_ID, timeout=10)
        session = dev.attach(pid)
        script = session.create_script(SCRIPT_JS)
        script.on('message', on_message)
        script.load()
        _state['script'] = script
        _state['session'] = session
        return script


def do_resolve(book_id, item_id, timeout=20.0):
    s = ensure_session()
    r = json.loads(s.exports_sync.fire(book_id, item_id))
    if 'err' in r:
        return r
    deadline = time.time() + timeout
    while time.time() < deadline:
        c = json.loads(s.exports_sync.check(item_id))
        if c.get('ok'):
            return {'item_id': item_id, 'json': c['json']}
        time.sleep(0.4)
    return {'err': 'timeout', 'item_id': item_id}


def do_chapters(book_id, timeout=60.0, limit=None):
    """获取章节目录：h() 拿全部 item_id，i() 分批补标题/时长。
    limit：只补前 N 章详情（大书全量需 46+ 批，宿主会超时；
    书入口展开第一章传 20，专辑页传 100）。"""
    s = ensure_session()
    r = json.loads(s.exports_sync.firedir(book_id))
    if 'err' in r:
        return r
    # 第一阶段：等目录响应（code=0）
    dir_j = ''
    deadline = time.time() + 25.0
    while time.time() < deadline:
        j = s.exports_sync.dirjson()
        if j and '"waiting"' not in j[:30]:
            try:
                dj = json.loads(j)
                if str(dj.get('code')) == '0':
                    dir_j = j
                    break
            except Exception:
                pass
        time.sleep(0.4)
    if not dir_j:
        return {'err': 'dir timeout', 'book_id': book_id}

    dd = json.loads(dir_j).get('data') or {}
    id_list = [str(x.get('item_id')) for x in (dd.get('item_data_list') or []) if x.get('item_id')]
    chapters = {}
    for it in (dd.get('item_detail_data_list') or []):
        iid = str(it.get('item_id'))
        if iid:
            chapters[iid] = {'item_id': iid, 'title': it.get('title'), 'order': it.get('order'),
                             'duration': (it.get('audio_info') or {}).get('duration')}

    # 第二阶段：分批拉详情补标题（limit 截断，避免大书超时）
    missing = [i for i in id_list if i not in chapters]
    if limit:
        missing = missing[:limit]
    BATCH = 20
    s.exports_sync.clearinfo()
    for bi in range(0, len(missing), BATCH):
        batch = missing[bi:bi + BATCH]
        rr = json.loads(s.exports_sync.fireinfo(book_id, json.dumps(batch)))
        if 'err' in rr:
            return {'err': 'fireinfo: ' + rr['err'], 'book_id': book_id}
        time.sleep(0.3)
    # 轮询收集（以 missing 集合为完成标准）
    want = set(missing)
    deadline = time.time() + timeout
    while time.time() < deadline and any(i in want and i not in chapters for i in id_list):
        try:
            items = json.loads(s.exports_sync.infojson()).get('items') or []
        except Exception:
            items = []
        for raw in items:
            try:
                d = json.loads(raw).get('data') or {}
                lst = d if isinstance(d, list) else (d.get('item_detail_data_list') or [])
                for it in lst:
                    iid = str(it.get('item_id'))
                    if iid and (iid not in chapters or not chapters[iid].get('title')):
                        chapters[iid] = {'item_id': iid, 'title': it.get('title'), 'order': it.get('order'),
                                         'duration': (it.get('audio_info') or {}).get('duration')}
            except Exception:
                pass
        time.sleep(0.5)

    out = [chapters[i] for i in id_list if i in chapters]
    bk = dd.get('book_info') or {}
    return {'book_id': book_id,
            'total': len(id_list),
            'titled': sum(1 for c in out if c.get('title')),
            'chapters': out,
            'book_info': {'book_name': bk.get('book_name'), 'author': bk.get('author'),
                          'anchor': bk.get('anchor'), 'thumb_url': bk.get('thumb_url'),
                          'abstract': bk.get('abstract'), 'tags': bk.get('tags'),
                          'score': bk.get('score'), 'serial_count': bk.get('serial_count')}}


class Handler(BaseHTTPRequestHandler):
    def _json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()
        self.wfile.write(body)
        self.wfile.flush()

    def do_GET(self):
        if self.path.startswith('/health'):
            try:
                s = ensure_session()
                self._json(200, {'ok': True, 'rpc': s.exports_sync.ping(),
                                 'svc': True})
            except Exception as e:
                self._json(503, {'ok': False, 'err': str(e)})
        elif self.path.startswith('/dumpsvc'):
            try:
                s = ensure_session()
                body = s.exports_sync.dumpsvc()
                self._json(200, {'sig': body.split('\n')})
            except Exception as e:
                self._json(500, {'err': str(e)})
        else:
            self._json(404, {'err': 'not found'})

    def do_POST(self):
        try:
            n = int(self.headers.get('Content-Length', 0))
            payload = json.loads(self.rfile.read(n).decode('utf-8') or '{}')
        except Exception as e:
            self._json(400, {'err': 'bad json: ' + str(e)})
            return

        book_id = str(payload.get('book_id', ''))
        if not book_id:
            self._json(400, {'err': 'book_id required'})
            return

        # 章节目录端点（limit：只补前 N 章详情）
        if self.path.startswith('/chapters'):
            try:
                r = do_chapters(book_id, limit=payload.get('limit'))
                if 'err' in r:
                    self._json(502, r)
                    return
                self._json(200, r)
            except Exception as e:
                self._json(500, {'err': str(e)})
            return

        item_ids = payload.get('item_ids') or []
        if not item_ids:
            self._json(400, {'err': 'item_ids required'})
            return

        try:
            raw = do_resolve(book_id, str(item_ids[0]))
            if 'err' in raw:
                self._json(502, raw)
                return
            vm = raw.get('json') or ''
            vj = json.loads(vm) if vm else {}
            urls = []
            for v in (vj.get('video_list') or []):
                meta = v.get('video_meta') or {}
                urls.append({
                    'quality': meta.get('quality'),
                    'size': meta.get('size'),
                    'url': v.get('main_url') or v.get('url'),
                })
            self._json(200, {
                'item_id': raw.get('item_id'),
                'expire': vj.get('url_expire'),
                'duration': vj.get('video_duration'),
                'media_type': vj.get('media_type'),
                'urls': urls,
                'raw': vj if self.path.startswith('/raw') else None,
            })
        except Exception as e:
            self._json(500, {'err': str(e)})

    def log_message(self, fmt, *args):
        print('[http]', fmt % args, flush=True)


def main():
    srv = ThreadingHTTPServer(('0.0.0.0', PORT), Handler)
    print(f'proxy v2 listening on :{PORT}', flush=True)
    srv.serve_forever()


if __name__ == '__main__':
    main()
