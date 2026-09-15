#!/usr/bin/env node
/**
 * 源脚本 Ed25519 签名校验（CI 用，与 APK 内 SourceVerifier 同一契约）。
 * 用法：node verify_sig.js <源脚本> <公钥文件(SPKI DER Base64)>
 */
'use strict';
const fs = require('fs');
const crypto = require('crypto');

const [scriptPath, pubPath] = process.argv.slice(2);
if (!scriptPath || !pubPath) {
  console.error('用法: node verify_sig.js <源脚本> <公钥文件>');
  process.exit(2);
}

const HEADER = '//qmu-sig:v1:';
const text = fs.readFileSync(scriptPath, 'utf8');
if (!text.startsWith(HEADER)) {
  console.error('VERIFY FAILED: 缺少签名头（请用 tools/source-signing/SourceSign.java sign 签名）');
  process.exit(1);
}
const nl = text.indexOf('\n');
if (nl <= HEADER.length) {
  console.error('VERIFY FAILED: 签名格式错误');
  process.exit(1);
}
const sig = Buffer.from(text.slice(HEADER.length, nl).trim(), 'base64');
const payload = Buffer.from(text.slice(nl + 1), 'utf8');
const der = Buffer.from(fs.readFileSync(pubPath, 'utf8').trim(), 'base64');
const key = crypto.createPublicKey({ key: der, format: 'der', type: 'spki' });

const ok = crypto.verify(null, payload, key, sig);
console.log(ok ? 'VERIFY OK' : 'VERIFY FAILED: 签名不匹配');
process.exit(ok ? 0 : 1);
