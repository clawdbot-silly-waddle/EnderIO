package crazypants.enderio.item.darksteel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.command.IEntitySelector;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemShears;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.IShearable;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.world.BlockEvent;

import com.enderio.core.api.client.gui.IAdvancedTooltipProvider;
import com.enderio.core.common.util.BlockCoord;
import com.enderio.core.common.util.ItemUtil;

import cofh.api.energy.IEnergyContainerItem;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import crazypants.enderio.EnderIO;
import crazypants.enderio.EnderIOTab;
import crazypants.enderio.config.Config;
import crazypants.enderio.item.darksteel.upgrade.EnergyUpgrade;

public class ItemDarkSteelShears extends ItemShears
        implements IEnergyContainerItem, IAdvancedTooltipProvider, IDarkSteelItem {

    public static boolean isEquipped(EntityPlayer player) {
        if (player == null) {
            return false;
        }
        ItemStack equipped = player.getCurrentEquippedItem();
        if (equipped == null) {
            return false;
        }
        return equipped.getItem() instanceof ItemDarkSteelShears;
    }

    public static boolean isEquippedAndPowered(EntityPlayer player, int requiredPower) {
        return getStoredPower(player) > requiredPower;
    }

    public static int getStoredPower(EntityPlayer player) {
        if (!isEquipped(player)) {
            return 0;
        }
        return EnergyUpgrade.getEnergyStored(player.getCurrentEquippedItem());
    }

    public static ItemDarkSteelShears create() {
        ItemDarkSteelShears res = new ItemDarkSteelShears();
        res.init();
        return res;
    }

    private final MultiHarvestComparator harvestComparator = new MultiHarvestComparator();
    private final EntityComparator entityComparator = new EntityComparator();
    protected String name;

    protected ItemDarkSteelShears(String name) {
        super();
        this.name = name;
        this.setMaxDamage(this.getMaxDamage() * Config.darkSteelShearsDurabilityFactor);
        setCreativeTab(EnderIOTab.tabEnderIO);
        String str = name + "_shears";
        setUnlocalizedName(str);
        setTextureName("enderIO:" + str);
    }

    protected ItemDarkSteelShears() {
        this("darkSteel");
    }

    @Override
    public int getIngotsRequiredForFullRepair() {
        return 2;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void getSubItems(Item item, CreativeTabs par2CreativeTabs, List<ItemStack> par3List) {
        ItemStack is = new ItemStack(this);
        par3List.add(is);

        is = new ItemStack(this);
        EnergyUpgrade.EMPOWERED_FOUR.writeToItem(is);
        EnergyUpgrade.setPowerFull(is);
        par3List.add(is);
    }

    @Override
    public boolean isDamaged(ItemStack stack) {
        return false;
    }

    @Override
    public boolean onBlockStartBreak(ItemStack itemstack, int x, int y, int z, EntityPlayer player) {
        if (player.worldObj.isRemote) {
            return false;
        }

        int powerStored = getStoredPower(player);
        if (powerStored < Config.darkSteelShearsPowerUsePerDamagePoint) {
            return super.onBlockStartBreak(itemstack, x, y, z, player);
        }

        Block block = player.worldObj.getBlock(x, y, z);
        if (!(block instanceof IShearable) || !((IShearable) block).isShearable(itemstack, player.worldObj, x, y, z)) {
            return super.onBlockStartBreak(itemstack, x, y, z, player);
        }

        // Scan for nearby shearable blocks, excluding the clicked position
        List<BlockCoord> targets = new ArrayList<BlockCoord>();
        int range = Config.darkSteelShearsBlockAreaBoostWhenPowered;
        for (int dx = -range; dx <= range; dx++) {
            for (int dy = -range; dy <= range; dy++) {
                for (int dz = -range; dz <= range; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    int bx = x + dx, by = y + dy, bz = z + dz;
                    Block block2 = player.worldObj.getBlock(bx, by, bz);
                    if (block2 instanceof IShearable
                            && ((IShearable) block2).isShearable(itemstack, player.worldObj, bx, by, bz)) {
                        targets.add(new BlockCoord(bx, by, bz));
                    }
                }
            }
        }

        harvestComparator.refPoint = new BlockCoord(x, y, z);
        Collections.sort(targets, harvestComparator);

        // Shear the original block (super handles drops, damage, stats)
        super.onBlockStartBreak(itemstack, x, y, z, player);
        if (itemstack.stackSize <= 0) return true;
        // Remove immediately with proper lifecycle hooks to trigger neighbor updates
        removeSheared(player.worldObj, block, x, y, z, player);

        // Process additional blocks with permission checks and proper lifecycle
        EntityPlayerMP playerMP = (player instanceof EntityPlayerMP) ? (EntityPlayerMP) player : null;
        int blocksProcessed = 1;
        int maxBlocks = powerStored / Config.darkSteelShearsPowerUsePerDamagePoint;

        for (int i = 0; i < targets.size() && blocksProcessed < maxBlocks; i++) {
            BlockCoord bc2 = targets.get(i);
            Block target = player.worldObj.getBlock(bc2.x, bc2.y, bc2.z);
            if (!(target instanceof IShearable)
                    || !((IShearable) target).isShearable(itemstack, player.worldObj, bc2.x, bc2.y, bc2.z)) {
                continue;
            }

            // Respect chunk claims via BreakEvent
            if (playerMP != null) {
                BlockEvent.BreakEvent event = ForgeHooks.onBlockBreakEvent(
                        player.worldObj,
                        playerMP.theItemInWorldManager.getGameType(),
                        playerMP,
                        bc2.x,
                        bc2.y,
                        bc2.z);
                if (event.isCanceled()) continue;
            }

            // Shear (drops, damage, stats) then remove with proper lifecycle
            super.onBlockStartBreak(itemstack, bc2.x, bc2.y, bc2.z, player);
            if (itemstack.stackSize <= 0) break;
            removeSheared(player.worldObj, target, bc2.x, bc2.y, bc2.z, player);
            blocksProcessed++;
        }

        return true;
    }

    private static void removeSheared(World world, Block block, int x, int y, int z, EntityPlayer player) {
        int meta = world.getBlockMetadata(x, y, z);
        world.playAuxSFXAtEntity(player, 2001, x, y, z, Block.getIdFromBlock(block) + (meta << 12));
        block.onBlockHarvested(world, x, y, z, meta, player);
        if (block.removedByPlayer(world, player, x, y, z, true)) {
            block.onBlockDestroyedByPlayer(world, x, y, z, meta);
        }
    }

    IEntitySelector selectShearable = new IEntitySelector() {

        @Override
        public boolean isEntityApplicable(Entity entity) {
            return entity instanceof IShearable && ((IShearable) entity)
                    .isShearable(null, entity.worldObj, (int) entity.posX, (int) entity.posY, (int) entity.posZ);
        }
    };

    @SuppressWarnings("unchecked")
    @Override
    public boolean itemInteractionForEntity(ItemStack itemstack, EntityPlayer player, EntityLivingBase entity) {
        if (entity.worldObj.isRemote) {
            return false;
        }

        int powerStored = getStoredPower(player);
        if (powerStored < Config.darkSteelShearsPowerUsePerDamagePoint) {
            return super.itemInteractionForEntity(itemstack, player, entity);
        }

        if (entity instanceof IShearable) {
            AxisAlignedBB bb = AxisAlignedBB.getBoundingBox(
                    entity.posX - Config.darkSteelShearsEntityAreaBoostWhenPowered,
                    entity.posY - Config.darkSteelShearsEntityAreaBoostWhenPowered,
                    entity.posZ - Config.darkSteelShearsEntityAreaBoostWhenPowered,
                    entity.posX + Config.darkSteelShearsEntityAreaBoostWhenPowered,
                    entity.posY + Config.darkSteelShearsEntityAreaBoostWhenPowered,
                    entity.posZ + Config.darkSteelShearsEntityAreaBoostWhenPowered);
            List<IShearable> sortedTargets = new ArrayList<>(
                    entity.worldObj.selectEntitiesWithinAABB(IShearable.class, bb, selectShearable));
            entityComparator.refPoint = entity;
            Collections.sort((List<Entity>) (Object) sortedTargets, entityComparator);

            boolean result = false;
            int maxSheep = Math.min(sortedTargets.size(), powerStored / Config.darkSteelShearsPowerUsePerDamagePoint);
            for (int i = 0; i < maxSheep; i++) {
                Entity entity2 = (Entity) sortedTargets.get(i);
                if (entity2 instanceof EntityLivingBase
                        && super.itemInteractionForEntity(itemstack, player, (EntityLivingBase) entity2)) {
                    result = true;
                }
            }
            return result;
        }
        return false;
    }

    @Override
    public void setDamage(ItemStack stack, int newDamage) {
        int oldDamage = getDamage(stack);
        if (newDamage <= oldDamage) {
            super.setDamage(stack, newDamage);
        }
        int damage = newDamage - oldDamage;

        EnergyUpgrade eu = EnergyUpgrade.loadFromItem(stack);
        if (eu != null && eu.isAbsorbDamageWithPower(stack) && eu.getEnergy() > 0) {
            eu.extractEnergy(damage * Config.darkSteelShearsPowerUsePerDamagePoint, false);
        } else {
            super.setDamage(stack, newDamage);
        }
        if (eu != null) {
            eu.writeToItem(stack);
        }
    }

    protected void init() {
        GameRegistry.registerItem(this, getUnlocalizedName());
        MinecraftForge.EVENT_BUS.register(new EventHandler());
    }

    @Override
    public int receiveEnergy(ItemStack container, int maxReceive, boolean simulate) {
        return EnergyUpgrade.receiveEnergy(container, maxReceive, simulate);
    }

    @Override
    public int extractEnergy(ItemStack container, int maxExtract, boolean simulate) {
        return EnergyUpgrade.extractEnergy(container, maxExtract, simulate);
    }

    @Override
    public int getEnergyStored(ItemStack container) {
        return EnergyUpgrade.getEnergyStored(container);
    }

    @Override
    public int getMaxEnergyStored(ItemStack container) {
        return EnergyUpgrade.getMaxEnergyStored(container);
    }

    @Override
    public boolean getIsRepairable(ItemStack i1, ItemStack i2) {
        // return i2 != null && i2.getItem() == EnderIO.itemAlloy && i2.getItemDamage() == Alloy.DARK_STEEL.ordinal();
        return false;
    }

    @Override
    public int getItemEnchantability() {
        return ItemDarkSteelSword.MATERIAL.getEnchantability();
    }

    @Override
    public void addCommonEntries(ItemStack itemstack, EntityPlayer entityplayer, List<String> list, boolean flag) {
        DarkSteelRecipeManager.instance.addCommonTooltipEntries(itemstack, entityplayer, list, flag);
    }

    @Override
    public void addBasicEntries(ItemStack itemstack, EntityPlayer entityplayer, List<String> list, boolean flag) {
        DarkSteelRecipeManager.instance.addBasicTooltipEntries(itemstack, entityplayer, list, flag);
    }

    @Override
    public void addDetailedEntries(ItemStack itemstack, EntityPlayer entityplayer, List<String> list, boolean flag) {
        if (!Config.addDurabilityTootip) {
            list.add(ItemUtil.getDurabilityString(itemstack));
        }
        String str = EnergyUpgrade.getStoredEnergyString(itemstack);
        if (str != null) {
            list.add(str);
        }
        if (EnergyUpgrade.itemHasAnyPowerUpgrade(itemstack)) {
            list.add(EnderIO.lang.localize("item." + name + "_shears.tooltip.multiHarvest"));
            list.add(
                    EnumChatFormatting.WHITE + "+"
                            + Config.darkSteelShearsEffeciencyBoostWhenPowered
                            + " "
                            + EnderIO.lang.localize("item." + name + "_pickaxe.tooltip.effPowered"));
        }
        DarkSteelRecipeManager.instance.addAdvancedTooltipEntries(itemstack, entityplayer, list, flag);
    }

    public ItemStack createItemStack() {
        return new ItemStack(this);
    }

    private static class MultiHarvestComparator implements Comparator<BlockCoord> {

        BlockCoord refPoint;

        @Override
        public int compare(BlockCoord arg0, BlockCoord arg1) {
            int d1 = refPoint.getDistSq(arg0);
            int d2 = refPoint.getDistSq(arg1);
            return compare(d1, d2);
        }

        // NB: Copy of Integer.compare, which is only in Java 1.7+
        public static int compare(int x, int y) {
            return (x < y) ? -1 : ((x == y) ? 0 : 1);
        }
    }

    private static class EntityComparator implements Comparator<Entity> {

        Entity refPoint;

        @Override
        public int compare(Entity paramT1, Entity paramT2) {
            double distanceSqToEntity1 = refPoint.getDistanceSqToEntity(paramT1);
            double distanceSqToEntity2 = refPoint.getDistanceSqToEntity(paramT2);
            if (distanceSqToEntity1 < distanceSqToEntity2) return -1;
            if (distanceSqToEntity1 > distanceSqToEntity2) return 1;
            // Double.compare() does something with bits now, but for distances it's clear:
            // if it's neither farther nor nearer is same.
            return 0;
        }
    }

    public static class EventHandler {

        @SubscribeEvent
        public void onBreakSpeedEvent(PlayerEvent.BreakSpeed evt) {
            if (evt.originalSpeed > 2.0
                    && isEquippedAndPowered(evt.entityPlayer, Config.darkSteelShearsPowerUsePerDamagePoint)) {
                evt.newSpeed = evt.originalSpeed * Config.darkSteelShearsEffeciencyBoostWhenPowered;
            }
        }
    }
}
