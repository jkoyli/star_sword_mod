package cn.autoforged.star_sword_mod_1785589476.agent;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline name mapping between Mojang ("named") and SRG ("intermediate") members.
 *
 * <p>In production Forge 1.20.1 runs with SRG member names ({@code m_21223_}), while a
 * development client runs with Mojang names ({@code getHealth}). An agent has no
 * refmap mechanism, so it has to know both names up front. The table is read from
 * the mapping file the build already produces
 * ({@code build/moddev/artifacts/namedToIntermediate.tsrg}), with a hardcoded
 * fallback for the handful of members this proof of concept touches.
 *
 * <p>TSRG layout:
 * <pre>
 * net/minecraft/world/entity/LivingEntity net/minecraft/world/entity/LivingEntity
 * \tgetHealth ()F m_21223_
 * \thurt (Lnet/minecraft/world/damagesource/DamageSource;F)Z m_6469_
 * </pre>
 */
public final class MappingTable {

    /** class name (slashes) -> (member name + " " + descriptor -> srg name) */
    private final Map<String, Map<String, String>> methods;

    private MappingTable(Map<String, Map<String, String>> methods) {
        this.methods = methods;
    }

    public static MappingTable empty() {
        return new MappingTable(Map.of());
    }

    /**
     * Parses a TSRG file. Never throws: a missing or malformed file yields an empty
     * table and the caller falls back to {@link #candidates} with the raw name only.
     */
    public static MappingTable load(File file) {
        Map<String, Map<String, String>> table = new HashMap<>();
        if (file == null || !file.isFile()) {
            AgentLog.warn("mapping file not found: " + file);
            return new MappingTable(table);
        }
        String currentClass = null;
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                if (line.charAt(0) != '\t' && line.charAt(0) != ' ') {
                    currentClass = line.split(" ")[0];
                    table.computeIfAbsent(currentClass, k -> new HashMap<>());
                    continue;
                }
                if (currentClass == null) {
                    continue;
                }
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 3) {
                    table.get(currentClass).put(parts[0] + " " + parts[1], parts[2]);
                }
            }
        } catch (IOException e) {
            AgentLog.warn("could not read mapping file " + file, e);
        }
        AgentLog.info("mapping loaded: " + table.size() + " classes from " + file.getName());
        return new MappingTable(table);
    }

    /**
     * Hardcoded fallback for the POC targets, used when no mapping file is available
     * (for example on a production client, where the tsrg is not shipped).
     *
     * <p><b>为什么必须完整</b>：生产环境里方法名是 SRG（{@code m_21224_}）。之前这张表只写了
     * getHealth/hurt/setHealth 三项，agent 日志里就出现：
     * <pre>
     * no isDeadOrDying()Z on ...LivingEntity, skipped
     * no die(...)V on ...LivingEntity, skipped
     * no addAdditionalSaveData(...)V on ...LivingEntity, skipped
     * </pre>
     * 也就是大部分钩子从来没装上过。名字取自本工程 Mixin 生成的 refmap
     * （{@code namedToIntermediate.tsrg}），属于同一套 1.20.1 映射。
     */
    public static MappingTable fallback() {
        Map<String, Map<String, String>> table = new HashMap<>();

        Map<String, String> living = new HashMap<>();
        living.put("getHealth ()F", "m_21223_");
        // 注意：setHealth 的 SRG 是 m_21153_，不是 m_21150_。写错会让这条规则在生产环境
        // 静默落到"方法不存在"，正好解释对账里 living.setHealth 出现在"不存在"一栏。
        living.put("setHealth (F)V", "m_21153_");
        living.put("hurt (Lnet/minecraft/world/damagesource/DamageSource;F)Z", "m_6469_");
        living.put("isDeadOrDying ()Z", "m_21224_");
        living.put("tickDeath ()V", "m_6153_");
        living.put("die (Lnet/minecraft/world/damagesource/DamageSource;)V", "m_6667_");
        living.put("dropAllDeathLoot (Lnet/minecraft/world/damagesource/DamageSource;)V", "m_6668_");
        living.put("addAdditionalSaveData (Lnet/minecraft/nbt/CompoundTag;)V", "m_7380_");
        living.put("readAdditionalSaveData (Lnet/minecraft/nbt/CompoundTag;)V", "m_7378_");
        table.put("net/minecraft/world/entity/LivingEntity", living);

        Map<String, String> entity = new HashMap<>();
        entity.put("remove (Lnet/minecraft/world/entity/Entity$RemovalReason;)V", "m_142687_");
        entity.put("setRemoved (Lnet/minecraft/world/entity/Entity$RemovalReason;)V", "m_142467_");
        entity.put("isRemoved ()Z", "m_213877_");
        entity.put("isAlive ()Z", "m_6084_");
        table.put("net/minecraft/world/entity/Entity", entity);

        // Player / ServerPlayer 只声明了 die 覆写（getHealth / isDeadOrDying 是继承来的，
        // 规则会正确地落到"方法不存在"）。缺这一节会让两条 die 闸门在生产环境整条消失。
        Map<String, String> playerDie = new HashMap<>();
        playerDie.put("die (Lnet/minecraft/world/damagesource/DamageSource;)V", "m_6667_");
        table.put("net/minecraft/world/entity/player/Player", playerDie);
        table.put("net/minecraft/server/level/ServerPlayer", new HashMap<>(playerDie));

        // LocalPlayer **覆写**了 tickDeath（客户端就是这么把 deathTime 推到 1 的）。
        // 只声明 tickDeath：它的 getHealth/isDeadOrDying 是继承来的，规则会落到"方法不存在"。
        Map<String, String> localPlayer = new HashMap<>();
        localPlayer.put("tickDeath ()V", "m_6153_");
        table.put("net/minecraft/client/player/LocalPlayer", localPlayer);

        Map<String, String> synched = new HashMap<>();
        synched.put("set (Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/lang/Object;Z)V",
                "m_276349_");
        synched.put("set (Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/lang/Object;)V",
                "m_135381_");
        table.put("net/minecraft/network/syncher/SynchedEntityData", synched);

        Map<String, String> connection = new HashMap<>();
        connection.put("send (Lnet/minecraft/network/protocol/Packet;)V", "m_129512_");
        connection.put("send (Lnet/minecraft/network/protocol/Packet;"
                + "Lnet/minecraft/network/PacketSendListener;)V", "m_243124_");
        table.put("net/minecraft/network/Connection", connection);

        return new MappingTable(table);
    }

    /**
     * Candidate runtime names for a member, most specific first: the SRG name from the
     * table (production) followed by the name as written (development).
     */
    public List<String> candidates(String ownerSlashed, String methodName, String descriptor) {
        List<String> names = new ArrayList<>(2);
        Map<String, String> members = methods.get(ownerSlashed);
        if (members != null) {
            String srg = members.get(methodName + " " + descriptor);
            if (srg != null) {
                names.add(srg);
            }
        }
        if (!names.contains(methodName)) {
            names.add(methodName);
        }
        return Collections.unmodifiableList(names);
    }

    public boolean isEmpty() {
        return methods.isEmpty();
    }

    /** 表里有多少个类（用于启动时区分"文件表"与"内置表"，并给出规模）。 */
    public int size() {
        return methods.size();
    }
}
