# -*- coding: utf-8 -*-
"""验证直链可下载（Range 请求 + ftyp 魔数）。"""
import json
import urllib.request

payload = json.dumps({"book_id": "7621481208274226201", "item_ids": ["7629142497800749630"]}).encode()
req = urllib.request.Request("http://127.0.0.1:8791/resolve", data=payload, method='POST')
req.add_header('Content-Type', 'application/json')
with urllib.request.urlopen(req, timeout=60) as r:
    data = json.loads(r.read().decode('utf-8'))

url = data['urls'][0]['url']
print('url ok, host:', url.split('/')[2])

# Range 请求前 32 字节
req2 = urllib.request.Request(url, headers={'Range': 'bytes=0-31'})
with urllib.request.urlopen(req2, timeout=30) as r:
    head = r.read()
    print('HTTP', r.status, 'len:', len(head))
    print('bytes:', head[:32].hex())
    ascii_part = ''.join(chr(b) if 32 <= b < 127 else '.' for b in head)
    print('ascii:', ascii_part)
    # m4a: offset 4-7 = 'ftyp'
    print('ftyp?', head[4:8] == b'ftyp')
