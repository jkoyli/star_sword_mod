package cn.autoforged.star_sword_mod_1785589476.mixin;

import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;
import java.util.UUID;

/**
 * Accessor Mixin：暴露 {@link PersistentEntitySectionManager} 的内部存储，供“移除模式”绕过
 * {@code Entity.remove} 直接摘除实体（其他模组可能拦截 {@code remove}）。
 *
 * <p>1.20.1 的字段实名与 SRG（实证自 {@code namedToIntermediate.tsrg:90705-90706}）：
 * <ul>
 *   <li>{@code visibleEntityStorage} = {@code f_157494_}，类型 {@link EntityLookup}（按 id/UUID 索引）；</li>
 *   <li>{@code sectionStorage} = {@code f_157495_}，类型 {@link EntitySectionStorage}（按 section 索引）；</li>
 *   <li>{@code knownUuids} = {@code f_157491_}，用于去重 UUID，手动移除实体后需一并清理。</li>
 * </ul>
 */
@Mixin(PersistentEntitySectionManager.class)
public interface PersistentEntitySectionManagerAccessor<T extends EntityAccess> {

    @Accessor("visibleEntityStorage")
    EntityLookup<T> starSword$getVisibleEntityStorage();

    @Accessor("sectionStorage")
    EntitySectionStorage<T> starSword$getSectionStorage();

    @Accessor("knownUuids")
    Set<UUID> starSword$getKnownUuids();
}
