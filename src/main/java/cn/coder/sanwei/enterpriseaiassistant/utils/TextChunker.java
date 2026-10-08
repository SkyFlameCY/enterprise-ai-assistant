package cn.coder.sanwei.enterpriseaiassistant.utils;

import cn.coder.sanwei.enterpriseaiassistant.exception.AiServiceException;
import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * TextChunker
 *
 * @author caiyang
 * @date 2026/9/30
 */
public class TextChunker {

    public static List<String> split(String text, int chunkSize, int overlap) {
        List<String> split = new ArrayList<>();

        if (StrUtil.isBlank(text)) {
            throw new IllegalArgumentException("空白文本");
        }
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize 不能为 0");
        }
        if (overlap < 0) {
            throw new IllegalArgumentException("overlap 不能小于 0");
        }
        if (chunkSize <= overlap) {
            throw new IllegalArgumentException("overlap 不能大于或等于 chunkSize");
        }

        int start = 0;
        int len = text.length();

        while (start < len) {
            int end = Math.min(start + chunkSize, text.length());
            split.add(text.substring(start, end));

            if (end == len) {
                break;
            }

            start = end - overlap;
        }

        return split;
    }
}
