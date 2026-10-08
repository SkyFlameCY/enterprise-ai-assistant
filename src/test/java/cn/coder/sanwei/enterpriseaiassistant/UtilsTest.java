package cn.coder.sanwei.enterpriseaiassistant;

import cn.coder.sanwei.enterpriseaiassistant.utils.TextChunker;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UtilsTest
 *
 * @author caiyang
 * @date 2026/9/30
 */
public class UtilsTest {

    @Test
    void textChunkTest() {
        String text = "ABCDEFGHIJ";
        List<String> chunk = TextChunker.split(text, 4, 1);
        assertThat(chunk).isEqualTo(Arrays.asList("ABCD", "DEFG", "GHIJ"));
    }
}
