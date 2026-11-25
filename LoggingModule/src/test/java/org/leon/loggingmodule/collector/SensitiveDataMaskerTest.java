package org.leon.loggingmodule.collector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.leon.loggingmodule.config.AuditProperties;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataMaskerTest {

    private SensitiveDataMasker masker;

    @BeforeEach
    void setUp() {
        masker = new SensitiveDataMasker(new AuditProperties());
    }

    @Test
    void maskUri_hidesSensitiveQueryParamsAndPhone() {
        String masked = masker.maskUri("/auth/login",
                "username=leon&password=Secret123&token=abc.def.ghi&phone=13812345678", 512);
        assertThat(masked).contains("username=leon");
        assertThat(masked).contains("password=***");
        assertThat(masked).contains("token=***");
        assertThat(masked).contains("phone=***");
        assertThat(masked).doesNotContain("Secret123");
        assertThat(masked).doesNotContain("abc.def.ghi");
        assertThat(masked).doesNotContain("13812345678");
    }

    @Test
    void maskMessage_masksPhoneEmailIdCardAndBearer() {
        String raw = "login failed for phone=13812345678 email leon@example.com id 11010119900101123X Bearer eyJhbGciOiJIUzI1NiJ9";
        String masked = masker.maskMessage(raw, 512);
        assertThat(masked).contains("phone=***");
        assertThat(masked).contains("l***@example.com");
        assertThat(masked).contains("1101**********123X");
        assertThat(masked).contains("Bearer ***");
        assertThat(masked).doesNotContain("13812345678");
        assertThat(masked).doesNotContain("eyJhbGciOiJIUzI1NiJ9");
    }

    @Test
    void maskText_keepsPhonePrefixAndSuffix() {
        assertThat(masker.maskText("contact 13812345678 now")).isEqualTo("contact 138****5678 now");
    }

    @Test
    void maskName_keepsSurnameOnly() {
        assertThat(masker.maskName("张三丰")).isEqualTo("张**");
        assertThat(masker.maskName("Leon")).isEqualTo("L***");
    }
}
