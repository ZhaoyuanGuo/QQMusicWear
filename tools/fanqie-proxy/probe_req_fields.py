# -*- coding: utf-8 -*-
"""Probe73: 在 PAS.j 入口 dump MGetVideoModelRequest 填充后的全部字段（构造模板）。"""
import frida
import sys
import time

DEVICE_ID = 'bipnlzaa6xy9nbee'

HOOK_JS = r'''
Java.perform(function () {
    send('perform_ok');
    function S(x, n) {
        try {
            if (x === null || x === undefined) return 'null';
            var s = '' + x;
            return s.length > (n || 300) ? s.substring(0, n || 300) + '…' : s;
        } catch (e) { return '<err>'; }
    }

    function dumpFields(obj) {
        var parts = [];
        try {
            var cls = obj.getClass();
            while (cls && cls.getName() !== 'java.lang.Object') {
                var fs = cls.getDeclaredFields();
                for (var i = 0; i < fs.length; i++) {
                    var f = fs[i];
                    if (f.getName().indexOf('$') >= 0 || f.getName() === 'Companion') continue;
                    f.setAccessible(true);
                    try {
                        var v = f.get(obj);
                        parts.push(f.getName() + '=' + (v === null ? 'null' : S(v, 200)));
                    } catch (e) {}
                }
                cls = cls.getSuperclass();
            }
        } catch (e) { parts.push('ERR ' + e); }
        return parts.join('\n    ');
    }

    try {
        var P = Java.use('com.xs.fm.rpc.rpc.PlayerApiService');
        P.j.overloads.forEach(function (ov) {
            ov.implementation = function (req) {
                try {
                    send('PAS.j REQ_FIELDS:\n    ' + dumpFields(req));
                } catch (e) {}
                return ov.apply(this, arguments);
            };
        });
        send('hooked PAS.j (field dump)');
    } catch (e) { send('PASj ERR ' + e); }

    // 同时 dump VMRESP 完整 video_model（不限长度）
    try {
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
                        } catch (e) { vmJson = S(vm, 1000); }
                    }
                    send('VMRESP itemId=' + f1.get(this) + '\nVMJSON ' + vmJson.substring(0, 2500));
                } catch (e) {}
                return r;
            };
        });
        send('hooked VMRESP');
    } catch (e) { send('VMD ERR ' + e); }

    send('ALL_HOOKED');
});
'''

def on_message(message, data):
    if message['type'] == 'send':
        print(message['payload'], flush=True)
    elif message['type'] == 'error':
        print('[ERR]', message.get('stack') or message.get('description'), flush=True)

def main():
    dev = frida.get_device(DEVICE_ID, timeout=10)
    pid = int(sys.argv[1])
    session = dev.attach(pid)
    h = session.create_script(HOOK_JS)
    h.on('message', on_message)
    h.load()
    print('READY', flush=True)
    time.sleep(3600)

if __name__ == '__main__':
    main()
