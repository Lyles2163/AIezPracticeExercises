package org.leon.practicemodule.oj.pojo.vo;

import java.io.Serializable;

/** 示例用例。只有 is_sample=1 的用例才会出现在这里，隐藏用例绝不下发。 */
public record OjTestcaseSampleVO(Integer seq,
                                 String inputData,
                                 String expectedOutput) implements Serializable {
}
