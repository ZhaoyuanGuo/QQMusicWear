import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * QQMusicWear 音乐源签名工具（单文件，JDK 15+，无第三方依赖）。
 *
 * 用法：
 *   java SourceSign.java gen                       —— 生成 Ed25519 密钥对（写入本目录 *.key）
 *   java SourceSign.java sign <脚本路径>            —— 为源脚本追加/更新签名头（就地修改）
 *   java SourceSign.java verify <脚本路径> [公钥]   —— 校验脚本签名（默认用本目录公钥）
 *
 * 签名格式：脚本第一行为
 *   //qmu-sig:v1:<Base64签名>
 * 签名内容 = 第一行之后剩余的全部字节（UTF-8）。推送 GitHub 前必须签名，CI 会强制校验。
 */
public class SourceSign {

    static final String HEADER_PREFIX = "//qmu-sig:v1:";
    static final Path PUBLIC_KEY = Paths.get("source_signing_public.key");
    static final Path PRIVATE_KEY = Paths.get("source_signing_private.key");

    public static void main(String[] args) throws Exception {
        if (args.length == 0) usage();
        switch (args[0]) {
            case "gen" -> gen();
            case "sign" -> {
                if (args.length < 2) usage();
                sign(Paths.get(args[1]));
            }
            case "verify" -> {
                if (args.length < 2) usage();
                Path pub = args.length >= 3 ? Paths.get(args[2]) : PUBLIC_KEY;
                boolean ok = verify(Paths.get(args[1]), pub);
                System.out.println(ok ? "VERIFY OK" : "VERIFY FAILED");
                if (!ok) System.exit(1);
            }
            default -> usage();
        }
    }

    static void usage() {
        System.err.println("用法: java SourceSign.java gen|sign <脚本>|verify <脚本> [公钥]");
        System.exit(2);
    }

    // ---------------------------------------------------------------

    static void gen() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
        KeyPair kp = gen.generateKeyPair();
        Files.writeString(PUBLIC_KEY,
                Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()));
        Files.writeString(PRIVATE_KEY,
                Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()));
        System.out.println("公钥已写入: " + PUBLIC_KEY.toAbsolutePath());
        System.out.println("私钥已写入: " + PRIVATE_KEY.toAbsolutePath() + "（切勿提交/泄露）");
    }

    static void sign(Path script) throws Exception {
        byte[] priv = Base64.getDecoder().decode(Files.readString(PRIVATE_KEY).trim());
        var key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(priv));

        byte[] all = Files.readAllBytes(script);
        String text = new String(all, StandardCharsets.UTF_8);
        // 已有签名头则先剥离，再重签
        if (text.startsWith(HEADER_PREFIX)) {
            int nl = text.indexOf('\n');
            if (nl < 0) die("文件只有签名头，没有内容");
            text = text.substring(nl + 1);
        }
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);

        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(key);
        sig.update(payload);
        String b64 = Base64.getEncoder().encodeToString(sig.sign());

        Files.write(script, concat((HEADER_PREFIX + b64 + "\n").getBytes(StandardCharsets.UTF_8), payload));
        System.out.println("已签名: " + script.toAbsolutePath());
    }

    static boolean verify(Path script, Path pub) throws Exception {
        byte[] spki = Base64.getDecoder().decode(Files.readString(pub).trim());
        var key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));

        String text = Files.readString(script, StandardCharsets.UTF_8);
        if (!text.startsWith(HEADER_PREFIX)) return false;
        int nl = text.indexOf('\n');
        if (nl < 0) return false;
        String b64 = text.substring(HEADER_PREFIX.length(), nl).trim();
        byte[] payload = text.substring(nl + 1).getBytes(StandardCharsets.UTF_8);

        Signature sig = Signature.getInstance("Ed25519");
        sig.initVerify(key);
        sig.update(payload);
        return sig.verify(Base64.getDecoder().decode(b64));
    }

    // ---------------------------------------------------------------

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    static void die(String msg) throws IOException {
        throw new IOException(msg);
    }
}
