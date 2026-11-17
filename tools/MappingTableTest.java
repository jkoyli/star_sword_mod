import cn.autoforged.star_sword_mod_1785589476.agent.GuardTransformer;
import cn.autoforged.star_sword_mod_1785589476.agent.MappingTable;
import cn.autoforged.star_sword_mod_1785589476.agent.Rules;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 校验 agent 的**内置 SRG 表**与映射文件一致。
 *
 * <p>为什么需要：生产环境没有 {@code namedToIntermediate.tsrg}，agent 走 {@code MappingTable.fallback()}。
 * 内置表一旦与真实 SRG 漂移（例如把 setHealth 写成 {@code m_21150_} 而不是 {@code m_21153_}），
 * 对应规则会在对账里表现为"方法不存在"，防线静默消失——只有比对映射文件才能发现。
 *
 * <p><b>规则列表从 {@link Rules#bridge()} 推导，不再手抄一份。</b>早先这里是一张手写的
 * {@code RULES} 数组，新增规则时它不会跟着长，于是"新规则没人校验"这件事本身没有失败信号；
 * 现在改成遍历真实规则表，加规则即自动纳入校验。
 *
 * <p>运行：
 * <pre>
 * javac -encoding UTF-8 -d build/tools-test -cp agent/build/classes/java/main tools/MappingTableTest.java
 * java -cp "build/tools-test;agent/build/classes/java/main" MappingTableTest build/moddev/artifacts/namedToIntermediate.tsrg
 * </pre>
 */
public final class MappingTableTest {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("usage: MappingTableTest <namedToIntermediate.tsrg>");
            System.exit(2);
        }
        Map<String, Map<String, String>> tsrg = parseTsrg(Path.of(args[0]));
        MappingTable fallback = MappingTable.fallback();
        List<Rules.Rule> rules = Rules.forMode(GuardTransformer.Mode.BRIDGE);

        int checked = 0;
        int failed = 0;
        for (Rules.Rule rule : rules) {
            String owner = rule.owner().replace('.', '/');
            String key = rule.method() + " " + rule.descriptor();
            String expected = tsrg.getOrDefault(owner, Map.of()).get(key);
            if (expected == null) {
                // 映射文件里没有 = 这个类本来就不声明该方法（例如 Player.getHealth 是继承来的）。
                continue;
            }
            checked++;
            List<String> candidates = fallback.candidates(owner, rule.method(), rule.descriptor());
            boolean ok = !candidates.isEmpty() && expected.equals(candidates.get(0));
            System.out.println((ok ? "  ok   " : "  FAIL ")
                    + owner.substring(owner.lastIndexOf('/') + 1) + "." + rule.method()
                    + " 期望=" + expected + " 内置=" + candidates);
            if (!ok) {
                failed++;
            }
        }

        System.out.println("规则=" + rules.size() + " 与映射文件比对=" + checked + " failed=" + failed);
        System.out.println(failed == 0 ? "ALL PASS" : "FAILED");
        if (failed != 0) {
            System.exit(1);
        }
    }

    private static Map<String, Map<String, String>> parseTsrg(Path file) throws Exception {
        Map<String, Map<String, String>> table = new HashMap<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String current = null;
        for (String line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            if (line.charAt(0) != '\t') {
                current = line.split(" ")[0];
                table.computeIfAbsent(current, k -> new HashMap<>());
                continue;
            }
            if (current == null) {
                continue;
            }
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 3) {
                table.get(current).put(parts[0] + " " + parts[1], parts[2]);
            }
        }
        return table;
    }
}
