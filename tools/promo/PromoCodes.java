import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.Properties;

/**
 * Промокоды на «Качай Плюс» для соцсетей. Формат и проверка — app/.../billing/Promo.kt.
 *
 * Запуск из корня проекта (нужен JDK 11+, без сборки):
 *   java tools/promo/PromoCodes.java keygen
 *       — один раз: создаёт promo-key.properties (закрытый ключ, в git не попадает) и печатает строку
 *         promo.publicKey=… для secrets.properties. Ключ храните в копии, как ключ подписи APK:
 *         без него новые коды не сделать, а с ним любой сделает себе Плюс.
 *   java tools/promo/PromoCodes.java make 7 2027-01-10 [сколько]
 *       — коды на 7 дней Плюса, действуют по 10 января 2027 включительно. Каждый код — один раз на телефоне,
 *         но один и тот же код могут ввести разные люди: для поста в соцсетях хватит одного.
 *         Выданные коды дописываются в promo-codes.log (тоже не в git): номер кода виден в статистике.
 */
public class PromoCodes {
    static final Path KEY_FILE = Path.of("promo-key.properties");
    static final LocalDate EPOCH = LocalDate.of(2026, 1, 1);
    static final String PREFIX = "PLUS-";

    public static void main(String[] args) throws Exception {
        if (args.length >= 1 && args[0].equals("keygen")) {
            keygen();
        } else if (args.length >= 3 && args[0].equals("make")) {
            make(Integer.parseInt(args[1]), LocalDate.parse(args[2]), args.length >= 4 ? Integer.parseInt(args[3]) : 1);
        } else {
            System.out.println("java tools/promo/PromoCodes.java keygen");
            System.out.println("java tools/promo/PromoCodes.java make <дней Плюса> <действует до ГГГГ-ММ-ДД> [сколько кодов]");
        }
    }

    static void keygen() throws Exception {
        if (Files.exists(KEY_FILE)) {
            System.out.println(KEY_FILE + " уже есть — новый ключ сделает старые коды недействительными. Удалите файл вручную, если это нужно.");
            return;
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = g.generateKeyPair();
        Properties p = new Properties();
        p.setProperty("privateKey", Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()));
        p.setProperty("publicKey", Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()));
        try (FileOutputStream out = new FileOutputStream(KEY_FILE.toFile())) {
            p.store(out, "Kachai promo codes key. Keep secret, back it up, never commit");
        }
        System.out.println("Ключ сохранён в " + KEY_FILE + ". Добавьте в secrets.properties:");
        System.out.println("promo.publicKey=" + p.getProperty("publicKey"));
    }

    static void make(int days, LocalDate validUntil, int count) throws Exception {
        if (days < 1 || days > 255) throw new IllegalArgumentException("дней Плюса — от 1 до 255");
        long until = ChronoUnit.DAYS.between(EPOCH, validUntil);
        if (until < 0 || until > 0xFFFF) throw new IllegalArgumentException("дата действия — с 2026-01-01");
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(KEY_FILE.toFile())) {
            p.load(in);
        }
        PrivateKey key = KeyFactory.getInstance("EC")
            .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(p.getProperty("privateKey"))));
        SecureRandom random = new SecureRandom();
        try (FileWriter log = new FileWriter("promo-codes.log", true)) {
            for (int i = 0; i < count; i++) {
                int id = 1 + random.nextInt(0xFFFE);
                byte[] data = ByteBuffer.allocate(6)
                    .put((byte) 1).put((byte) days).putShort((short) until).putShort((short) id).array();
                Signature s = Signature.getInstance("SHA256withECDSA");
                s.initSign(key);
                s.update(data);
                byte[] raw = derToRaw(s.sign());
                byte[] all = Arrays.copyOf(data, data.length + raw.length);
                System.arraycopy(raw, 0, all, data.length, raw.length);
                String code = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(all);
                System.out.println(code);
                log.write(LocalDate.now() + "\tномер " + id + "\t" + days + " дн.\tдо " + validUntil + "\t" + code + "\n");
            }
        }
    }

    /** Java подписывает в DER; в код кладём короче — r и s по 32 байта. */
    static byte[] derToRaw(byte[] der) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(der);
        if (b.get() != 0x30) throw new IOException("bad signature");
        int len = b.get() & 0xFF;
        if (len > 0x80) b.position(b.position() + (len - 0x80));
        byte[] out = new byte[64];
        for (int part = 0; part < 2; part++) {
            if (b.get() != 0x02) throw new IOException("bad signature");
            byte[] v = new byte[b.get() & 0xFF];
            b.get(v);
            byte[] fixed = new BigInteger(1, v).toByteArray();
            int skip = fixed.length > 32 ? fixed.length - 32 : 0;
            System.arraycopy(fixed, skip, out, part * 32 + 32 - (fixed.length - skip), fixed.length - skip);
        }
        return out;
    }
}
