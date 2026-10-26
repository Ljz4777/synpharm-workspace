package com.synpharm.design.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多链序列哈希测试（06 §4.5）。
 *
 * <p>这个类的错误不会以异常形式暴露，只会让 S5 去重悄悄认错分子：
 * <ul>
 *   <li>哈希只看重链 → 轻链不同的两个抗体被合并，真候选丢失；</li>
 *   <li>哈希受输入顺序影响 → 同一条双抗两次提交得到两个哈希，去重完全失效。</li>
 * </ul>
 * 所以断言的重点不是"能算出一个串"，而是这三种等价/不等价关系。
 *
 * @author SynPharm Team
 */
class SequenceHasherTest {

    private static final String HEAVY = "EVQLVESGGGLVQPGGSLRLSCAASGFTFSSYAMSWVRQAPGKGLEWVS";
    private static final String LIGHT = "DIQMTQSPSSLSASVGDRVTITCRASQDISNYLAWYQQKPGKAPKLLIY";

    @Test
    @DisplayName("三条链全空：返回 null（小分子走 inchikey，不占用该字段）")
    void allEmptyIsNull() {
        assertThat(SequenceHasher.hash(null, null, null)).isNull();
        assertThat(SequenceHasher.hash("  ", "", List.of())).isNull();
        assertThat(SequenceHasher.hashSingle(null)).isNull();
    }

    @Test
    @DisplayName("重链相同、轻链不同：哈希必须不同（否则两个不同抗体被去重合并）")
    void lightChainMatters() {
        String a = SequenceHasher.hash(HEAVY, LIGHT, null);
        String b = SequenceHasher.hash(HEAVY, LIGHT.replace('D', 'E'), null);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("轻链相同、重链不同：哈希必须不同")
    void heavyChainMatters() {
        String a = SequenceHasher.hash(HEAVY, LIGHT, null);
        String b = SequenceHasher.hash(HEAVY.replace('E', 'D'), LIGHT, null);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("链的边界不会被拼错：heavy=AABB + light=CC 与 heavy=AABBCC + light 空 不应同哈希")
    void chainBoundaryIsUnambiguous() {
        String withSeparator = SequenceHasher.hash("AABB", "CC", null);
        String concatenated = SequenceHasher.hash("AABBCC", null, null);
        assertThat(withSeparator).isNotEqualTo(concatenated);
    }

    @Test
    @DisplayName("额外链顺序不影响结果（否则同一双抗两次提交得到两个哈希）")
    void extraChainOrderIrrelevant() {
        var chainA = new SequenceHasher.Chain("B", "GGG");
        var chainB = new SequenceHasher.Chain("A", "TTT");

        String first = SequenceHasher.hash(HEAVY, LIGHT, List.of(chainA, chainB));
        String second = SequenceHasher.hash(HEAVY, LIGHT, List.of(chainB, chainA));

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("额外链的名字参与哈希：同序列不同名字不是同一个链位")
    void extraChainNameMatters() {
        String vh = SequenceHasher.hash(null, null, List.of(new SequenceHasher.Chain("VH2", "GGG")));
        String vl = SequenceHasher.hash(null, null, List.of(new SequenceHasher.Chain("VL2", "GGG")));
        assertThat(vh).isNotEqualTo(vl);
    }

    @Test
    @DisplayName("空白、大小写、尾部终止符都规范化掉：同一分子写法不同也同哈希")
    void normalizationMakesSameMoleculeEqual() {
        String clean = SequenceHasher.hash(HEAVY, LIGHT, null);
        String messy = SequenceHasher.hash("  " + HEAVY.toLowerCase() + "\n", LIGHT + "**  ", null);
        assertThat(clean).isEqualTo(messy);
    }

    @Test
    @DisplayName("规范化不改中间序列：内部字符不被动过")
    void normalizeKeepsInnerSequence() {
        assertThat(SequenceHasher.normalize(" ac-def \n")).isEqualTo("AC-DEF");
        assertThat(SequenceHasher.normalize("ACDEF*")).isEqualTo("ACDEF");
        assertThat(SequenceHasher.normalize("ACDEF**")).isEqualTo("ACDEF");
        // 只在尾部去终止符，中间出现的 * 保留（那是真实的未知残基占位）
        assertThat(SequenceHasher.normalize("AC*EF")).isEqualTo("AC*EF");
    }

    @Test
    @DisplayName("哈希是 64 位小写十六进制")
    void hashFormat() {
        assertThat(SequenceHasher.hash(HEAVY, LIGHT, null)).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("单链入口与多链入口对同一条序列等价")
    void singleChainEquivalence() {
        assertThat(SequenceHasher.hashSingle(HEAVY)).isEqualTo(SequenceHasher.hash(HEAVY, null, null));
    }

    @Test
    @DisplayName("额外链里的空条目被忽略：不会让哈希凭空变化")
    void emptyExtraChainsIgnored() {
        String plain = SequenceHasher.hash(HEAVY, LIGHT, null);
        String withNoise = SequenceHasher.hash(HEAVY, LIGHT, List.of(
                new SequenceHasher.Chain("X", "   "),
                new SequenceHasher.Chain("Y", null)));
        assertThat(plain).isEqualTo(withNoise);
    }
}
