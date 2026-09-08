// 导入 JJWT 库中用于解析 JWT 载荷（Claims）的接口
import io.jsonwebtoken.Claims;
// 导入 JWT 过期时抛出的异常类
import io.jsonwebtoken.ExpiredJwtException;
// 导入 JJWT 的核心构建器和解析器入口类
import io.jsonwebtoken.Jwts;
// 导入 JWT 格式不正确时抛出的异常类
import io.jsonwebtoken.MalformedJwtException;
// 导入 JWT 不受支持时抛出的异常类
import io.jsonwebtoken.UnsupportedJwtException;
// 导入用于根据字节数组生成 HMAC 密钥的工具类
import io.jsonwebtoken.security.Keys;
// 导入 JWT 签名验证失败时抛出的异常类
import io.jsonwebtoken.security.SignatureException;
// 导入 JUnit 5 测试框架的 BeforeEach 注解，用于在每个测试方法前执行初始化
import org.junit.jupiter.api.BeforeEach;
// 导入 JUnit 5 测试框架的 Test 注解，用于标记测试方法
import org.junit.jupiter.api.Test;

// 导入 Java 加密扩展中的对称密钥接口
import javax.crypto.SecretKey;
// 导入标准字符集枚举，用于指定字符串的编码格式
import java.nio.charset.StandardCharsets;
// 导入日期类，用于设置 JWT 的签发时间和过期时间
import java.util.Date;
// 导入 HashMap 集合，用于存放 JWT 的自定义声明（Payload）
import java.util.HashMap;
// 导入 Map 接口
import java.util.Map;

// 静态导入 JUnit 5 的断言方法，方便在测试中直接调用
import static org.junit.jupiter.api.Assertions.*;

// 定义 JWT 测试类
public class JwtTest {

    // ⚠️ 定义 HS256 算法所需的密钥字符串，要求至少 32 字节（256 位），生产环境建议使用随机生成的强密钥
    private static final String SECRET = "5p2O6Ziz6LaF57qn5peg5pWM5biFNjY2"; // 32 字节
    // 声明一个 SecretKey 类型的成员变量，用于存储转换后的 HMAC 密钥对象
    private SecretKey key;

    // 标记该方法为初始化方法，在每个 @Test 方法执行前自动运行
    @BeforeEach
    void setUp() {
        // 将字符串密钥转换为 UTF-8 字节数组，再通过 Keys 工具类生成适用于 HMAC-SHA 算法的 SecretKey 对象
        key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    // 测试用例 1：测试生成 JWT 字符串
    @Test
    void testGenerateJWT() {
        // 创建一个 HashMap 用于存放自定义的 Payload 声明数据
        Map<String, Object> claims = new HashMap<>();
        // 向 claims 中放入用户 ID
        claims.put("id", 1);
        // 向 claims 中放入用户名
        claims.put("name", "admin");

        // 使用 Jwts 构建器链式调用生成 JWT
        String jwt = Jwts.builder()
                // 将上面创建的自定义声明放入 JWT 的 Payload 部分
                .claims(claims)
                // 设置 JWT 的主题（Subject），通常用于标识该 Token 的用途
//                .subject("user-login")
                // 设置 JWT 的签发时间（Issued At）为当前时间
                .issuedAt(new Date())
                // 设置 JWT 的过期时间（Expiration）为当前时间往后推 1 小时（3600秒 * 1000毫秒）
                .expiration(new Date(System.currentTimeMillis() + 3600 * 1000))
                // 使用前面生成的 SecretKey 对 JWT 进行签名（默认使用 HS256 算法）
                .signWith(key)
                // 将 Header、Payload、Signature 三部分拼接并用 Base64Url 编码，生成最终的 JWT 字符串
                .compact();

        // 在控制台打印生成的 JWT，方便调试查看
        System.out.println("生成的 JWT: " + jwt);
        // 断言：生成的 JWT 字符串不能为 null
        assertNotNull(jwt);
        // 断言：JWT 字符串按 "." 分割后必须正好是 3 部分（Header.Payload.Signature）
        assertEquals(3, jwt.split("\\.").length);
    }

    // 测试用例 2：测试解析 JWT 并提取信息
    @Test
    void testParseJWT() {
        // 调用辅助方法生成一个有效期为 1 小时的 JWT 字符串
        String jwt = createToken(3600 * 1000);

        // 使用 Jwts 解析器链式调用解析 JWT
        Claims claims = Jwts.parser()
                // 设置用于验证签名的密钥（必须与生成时使用的密钥一致）
                .verifyWith(key)
                // 构建解析器实例
                .build()
                // 解析 JWT 字符串并验证签名，获取包含签名信息的 Jws 对象，再提取出 Payload（Claims）
                .parseSignedClaims(jwt)
                // 获取 Payload 部分的声明数据
                .getPayload();

        // 在控制台打印解析出的 Claims 对象
        System.out.println("解析结果: " + claims);
        // 断言：从 Payload 中取出的 "id" 值等于 1
        assertEquals(1, claims.get("id"));
        // 断言：从 Payload 中取出的 "name" 值等于 "admin"
        assertEquals("admin", claims.get("name"));
        // 断言：从 Payload 中取出的标准主题字段等于 "user-login"
        assertEquals("user-login", claims.getSubject());
    }

    // 测试用例 3：测试解析已过期的 JWT 时是否抛出正确的异常
    @Test
    void testExpiredJWT() {
        // 调用辅助方法生成一个在 1 秒前就已经过期的 JWT（传入负数毫秒）
        String expiredJwt = createToken(-1000);

        // 断言：执行 Lambda 表达式中的代码时，必须抛出 ExpiredJwtException 异常
        assertThrows(ExpiredJwtException.class, () -> {
            // 尝试解析这个已过期的 JWT
            Jwts.parser()
                    // 设置验证密钥
                    .verifyWith(key)
                    // 构建解析器
                    .build()
                    // 解析签名声明，由于已过期，此处会触发异常
                    .parseSignedClaims(expiredJwt);
        });
    }

    // 测试用例 4：测试篡改 JWT 签名后解析是否失败
    @Test
    void testTamperedJWT() {
        // 生成一个正常的 JWT
        String jwt = createToken(3600 * 1000);
        // 故意在 JWT 字符串末尾追加一个字符 "x"，模拟数据被篡改
        String tampered = jwt + "x";

        // 断言：解析被篡改的 JWT 时，必须抛出 SignatureException（签名验证失败）
        assertThrows(SignatureException.class, () -> {
            Jwts.parser()
                    .verifyWith(key)
                    .build()
                    // 尝试解析篡改后的 JWT，签名校验不通过
                    .parseSignedClaims(tampered);
        });
    }

    // 测试用例 5：测试格式错误的 JWT 字符串
    @Test
    void testMalformedJWT() {
        // 断言：解析格式错误的字符串时，必须抛出 MalformedJwtException
        assertThrows(MalformedJwtException.class, () -> {
            Jwts.parser()
                    .verifyWith(key)
                    .build()
                    // 传入一个不符合 JWT 三段式 Base64Url 编码格式的字符串
                    .parseSignedClaims("not.a.jwt");
        });
    }

    // 私有辅助方法：用于在测试中快速生成指定过期时间的 Token
    // 参数 expireMillis：距离当前时间的过期毫秒数（正数为未来，负数为过去）
    private String createToken(long expireMillis) {
        // 创建存放自定义声明的 Map
        Map<String, Object> claims = new HashMap<>();
        // 放入测试用的用户 ID
        claims.put("id", 1);
        // 放入测试用的用户名
        claims.put("name", "admin");

        // 构建并返回 JWT 字符串
        return Jwts.builder()
                // 设置自定义声明
                .claims(claims)
                // 设置主题
                .subject("user-login")
                // 设置签发时间为当前时间
                .issuedAt(new Date())
                // 设置过期时间为：当前时间 + 传入的毫秒数
                .expiration(new Date(System.currentTimeMillis() + expireMillis))
                // 使用成员变量 key 进行签名
                .signWith(key)
                // 压缩生成最终的 JWT 字符串并返回
                .compact();
    }
}