package org.geysermc.generator;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonWriter;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.tuple.Pair;
import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.LevelEvent;
import org.geysermc.generator.state.StateMapper;
import org.geysermc.generator.state.StateRemapper;
import org.jetbrains.annotations.Nullable;
import org.reflections.Reflections;

import java.awt.*;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Type;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import static org.mockito.Mockito.*;

public class MappingsGenerator {

    public static final Map<String, BlockEntry> BLOCK_ENTRIES = new HashMap<>();
    public static final Map<String, ItemEntry> ITEM_ENTRIES = new HashMap<>();
    public static final Map<String, SoundEntry> SOUND_ENTRIES = new HashMap<>();
    public static final Map<String, String> FALLBACK_BIOMES = new HashMap<>();

    static {
        // Different names:
        FALLBACK_BIOMES.put("badlands", "mesa");
        FALLBACK_BIOMES.put("eroded_badlands", "mesa_bryce");
        FALLBACK_BIOMES.put("wooded_badlands", "mesa_plateau_stone");

        FALLBACK_BIOMES.put("nether_wastes", "hell");
        FALLBACK_BIOMES.put("soul_sand_valley", "soulsand_valley");

        FALLBACK_BIOMES.put("old_growth_birch_forest", "birch_forest_mutated");
        FALLBACK_BIOMES.put("old_growth_pine_taiga", "mega_taiga");
        FALLBACK_BIOMES.put("old_growth_spruce_taiga", "redwood_taiga_mutated");

        FALLBACK_BIOMES.put("snowy_beach", "cold_beach");
        FALLBACK_BIOMES.put("snowy_plains", "ice_plains");
        FALLBACK_BIOMES.put("snowy_taiga", "cold_taiga");

        FALLBACK_BIOMES.put("windswept_forest", "extreme_hills_plus_trees");
        FALLBACK_BIOMES.put("windswept_gravelly_hills", "extreme_hills_mutated");
        FALLBACK_BIOMES.put("windswept_hills", "extreme_hills");
        FALLBACK_BIOMES.put("windswept_savanna", "savanna_mutated");

        FALLBACK_BIOMES.put("dark_forest", "roofed_forest");
        FALLBACK_BIOMES.put("sparse_jungle", "jungle_edge");
        FALLBACK_BIOMES.put("ice_spikes", "ice_plains_spikes");
        FALLBACK_BIOMES.put("mushroom_fields", "mushroom_island");
        FALLBACK_BIOMES.put("swamp", "swampland");
        FALLBACK_BIOMES.put("stony_shore", "stone_beach");

        // Doesn't exist on bedrock:
        FALLBACK_BIOMES.put("end_barrens", "the_end");
        FALLBACK_BIOMES.put("end_highlands", "the_end");
        FALLBACK_BIOMES.put("end_midlands", "the_end");
        FALLBACK_BIOMES.put("small_end_islands", "the_end");
        FALLBACK_BIOMES.put("the_void", "river"); // Not related to the end. river has similar colours.
    }


    /**
     * Java to Bedrock block identifier overrides
     */
    public static final Map<String, String> BLOCK_OVERRIDES = new HashMap<>();

    static {
        // put block overrides here
    }

    public static final Map<String, String> JAVA_TO_BEDROCK_ITEM_OVERRIDE = new HashMap<>();
    public static final List<String> VALID_BEDROCK_ITEMS = new ArrayList<>();
    public static final Map<String, List<String>> STATES = new HashMap<>();
    private static final List<String> POTTABLE_BLOCK_IDENTIFIERS = new ArrayList<>();

    static {
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block instanceof FlowerPotBlock flowerPot) {
                Block plant = flowerPot.getContent();
                if (plant != Blocks.AIR) {
                    POTTABLE_BLOCK_IDENTIFIERS.add(BuiltInRegistries.BLOCK.getKey(plant).toString());
                }
            }
        }
    }

    // This ends up in collision.json
    // collision_index in blocks.json refers to this to prevent duplication
    // This helps to reduce file size
    public static final List<List<List<Double>>> COLLISION_LIST = Lists.newArrayList();

    private static final JsonArray ALL_PLANKS = new JsonArray();

    private static final Gson GSON = new Gson();

    private final Multimap<String, StateMapper<?>> stateMappers = HashMultimap.create();

    public void generateBlocks() {
        Reflections ref = new Reflections("org.geysermc.generator.state.type");
        for (Class<?> clazz : ref.getTypesAnnotatedWith(StateRemapper.class)) {
            try {
                StateMapper<?> stateMapper = (StateMapper<?>) clazz.getDeclaredConstructor().newInstance();
                this.stateMappers.put(clazz.getAnnotation(StateRemapper.class).value(), stateMapper);
            } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
                e.printStackTrace();
            }
        }
        try {
            NbtList<NbtMap> palette;
            File blockPalette = new File("palettes/blockpalette.nbt");
            if (!blockPalette.exists()) {
                System.out.println("Could not find block palette (blockpalette.nbt), please refer to the README in the palettes directory.");
                return;
            }

            try {
                InputStream stream = new FileInputStream(blockPalette);

                try (NBTInputStream nbtInputStream = new NBTInputStream(new DataInputStream(new GZIPInputStream(stream)))) {
                    NbtMap ret = (NbtMap) nbtInputStream.readTag();
                    palette = (NbtList<NbtMap>) ret.getList("blocks", NbtType.COMPOUND);
                }
            } catch (IOException e) {
                throw new RuntimeException("Unable to get blocks from block palette", e);
            }

            File mappings = new File("mappings/blocks.json");
            File collision = new File("mappings/collision.json");
            if (!mappings.exists()) {
                System.out.println("Could not find mappings submodule! Did you clone them?");
                return;
            }

            try {
                Type mapType = new TypeToken<Map<String, BlockEntry>>() {}.getType();
                Map<String, BlockEntry> map = GSON.fromJson(new FileReader(mappings), mapType);
                BLOCK_ENTRIES.putAll(map);
            } catch (FileNotFoundException ex) {
                ex.printStackTrace();
            }

            for (NbtMap entry : palette) {
                String identifier = entry.getString("name");
                if (!STATES.containsKey(identifier)) {
                    NbtMap states = entry.getCompound("states");
                    List<String> stateKeys = new ArrayList<>(states.keySet());
                    // ignore some useless keys
                    stateKeys.remove("stone_slab_type");
                    STATES.put(identifier, stateKeys);
                }
            }
            // Some State Corrections
            STATES.put("minecraft:attached_pumpkin_stem", Arrays.asList("growth", "facing_direction"));
            STATES.put("minecraft:attached_melon_stem", Arrays.asList("growth", "facing_direction"));

            GsonBuilder builder = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping();
            JsonObject rootObject = new JsonObject();

            for (BlockState blockState : getAllStates()) {
                rootObject.add(blockStateToString(blockState), getRemapBlock(blockState, blockStateToString(blockState)));
            }

            FileWriter writer = new FileWriter(mappings);
            builder.create().toJson(rootObject, writer);
            writer.close();

            // Write collision types
            writer = new FileWriter(collision);
            builder.create().toJson(COLLISION_LIST, writer);
            writer.close();

            System.out.println("Some block states need to be manually mapped, please search for MANUALMAP in blocks.json, if there are no occurrences you do not need to do anything.");
            System.out.println("Finished block writing process!");
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public void generateItems() {
        try {
            File mappings = new File("mappings/items.json");
            File itemPalette = new File("palettes/runtime_item_states.json");
            if (!mappings.exists()) {
                System.out.println("Could not find mappings submodule! Did you clone them?");
                return;
            }
            if (!itemPalette.exists()) {
                System.out.println("Could not find item palette (runtime_item_states.json), please refer to the README in the palettes directory.");
                return;
            }

            try {
                Type mapType = new TypeToken<Map<String, ItemEntry>>() {}.getType();
                Map<String, ItemEntry> map = GSON.fromJson(new FileReader(mappings), mapType);
                ITEM_ENTRIES.putAll(map);
            } catch (FileNotFoundException ex) {
                ex.printStackTrace();
            }

            try {
                Type listType = new TypeToken<List<PaletteItemEntry>>(){}.getType();
                List<PaletteItemEntry> entries = GSON.fromJson(new FileReader(itemPalette), listType);
                entries.forEach(item -> VALID_BEDROCK_ITEMS.add(item.getIdentifier()));
                // Fix some discrepancies - key is the Java string and value is the Bedrock string

                // Conflicts
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:grass", "minecraft:tallgrass"); // Conflicts with grass block
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:grass_block", "minecraft:grass");
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:map", "minecraft:empty_map"); // Conflicts with filled map
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:melon", "minecraft:melon_block"); // Conflicts with melon slice
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:nether_brick", "minecraft:netherbrick"); // This is the item; the block conflicts
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:nether_bricks", "minecraft:nether_brick");
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:snow", "minecraft:snow_layer"); // Conflicts with snow block
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:snow_block", "minecraft:snow");
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:stone_stairs", "minecraft:normal_stone_stairs"); // Conflicts with cobblestone stairs
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:cobblestone_stairs", "minecraft:stone_stairs");
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:stonecutter", "minecraft:stonecutter_block"); // Conflicts with, surprisingly, the OLD MCPE stonecutter

                // Changed names
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:frogspawn", "minecraft:frog_spawn");
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:glow_item_frame", "minecraft:glow_frame");
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:item_frame", "minecraft:frame");
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:oak_door", "minecraft:wooden_door");
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:shulker_box", "minecraft:undyed_shulker_box");
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:small_dripleaf", "minecraft:small_dripleaf_block");
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:waxed_copper_block", "minecraft:waxed_copper");
                JAVA_TO_BEDROCK_ITEM_OVERRIDE.put("minecraft:zombified_piglin_spawn_egg", "minecraft:zombie_pigman_spawn_egg");
            } catch (FileNotFoundException ex) {
                ex.printStackTrace();
            }

            GsonBuilder builder = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping();
            JsonObject rootObject = new JsonObject();

            for (int i = 0; i < BuiltInRegistries.ITEM.size(); i++) {
                Item value = BuiltInRegistries.ITEM.byId(i);
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(value);
                if (key.getPath().endsWith("planks")) {
                    ALL_PLANKS.add(key.toString());
                }
            }

            for (int i = 0; i < BuiltInRegistries.ITEM.size(); i++) {
                Item value = BuiltInRegistries.ITEM.byId(i);
                String key = BuiltInRegistries.ITEM.getKey(value).toString();
                rootObject.add(key, getRemapItem(key, value, Block.byItem(value)));
            }

            FileWriter writer = new FileWriter(mappings);
            builder.create().toJson(rootObject, writer);
            writer.close();
            System.out.println("Finished item writing process!");

            // Check for duplicate mappings
            Map<JsonElement, String> itemDuplicateCheck = new HashMap<>();
            for (Map.Entry<String, JsonElement> object : rootObject.entrySet()) {
                if (itemDuplicateCheck.containsKey(object.getValue())) {
                    System.out.println("Possible duplicate items (" + object.getKey() + " and " + itemDuplicateCheck.get(object.getValue()) + ") in mappings: " + object.getValue());
                } else {
                    itemDuplicateCheck.put(object.getValue(), object.getKey());
                }
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public void generateSounds() {
        try {
            File mappings = new File("mappings/sounds.json");
            if (!mappings.exists()) {
                System.out.println("Could not find mappings submodule! Did you clone them?");
                return;
            }

            try {
                Type mapType = new TypeToken<Map<String, SoundEntry>>() {}.getType();
                Map<String, SoundEntry> map = GSON.fromJson(new FileReader(mappings), mapType);
                SOUND_ENTRIES.putAll(map);
            } catch (FileNotFoundException ex) {
                ex.printStackTrace();
                return;
            }

            Set<String> validBedrockSounds;
            FileSystem fileSystem = FileSystems.newFileSystem(Paths.get("bedrockresourcepack.zip"));

            try (InputStream stream = fileSystem.provider().newInputStream(fileSystem.getPath("sounds/sound_definitions.json"))) {
                JsonObject json = JsonParser.parseString(new String(stream.readAllBytes())).getAsJsonObject();
                validBedrockSounds = new HashSet<>(json.getAsJsonObject("sound_definitions").keySet());
            }

            GsonBuilder builder = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping();
            JsonObject rootObject = new JsonObject();

            for (SoundEvent soundEvent : BuiltInRegistries.SOUND_EVENT) {
                ResourceLocation key = BuiltInRegistries.SOUND_EVENT.getKey(soundEvent);

                String path = key.getPath();
                SoundEntry entry = SOUND_ENTRIES.get(key.getPath());

                if (entry == null) {
                    entry = new SoundEntry(null, null, -1, null, false);
                }

                // update the playsound, only if a valid bedrock mapping is found
                updatePlaySound(entry, path, validBedrockSounds);

                boolean validPlaySound = true;
                if (isBlank(entry.getPlaySound())) {
                    validPlaySound = false;
                } else if (!validBedrockSounds.contains(entry.getPlaySound())) {
                    System.out.printf("Invalid bedrock playsound for mapping: %50s -> %s%n", path, entry.getPlaySound());
                    validPlaySound = false;
                }

                // Auto map place block sounds
                if (!validPlaySound && isBlank(entry.getEventSound()) && path.startsWith("block") && path.endsWith("place")) {
                    if (entry.getIdentifier() == null || entry.getIdentifier().isEmpty()) {
                        Block block = BuiltInRegistries.BLOCK.get(new ResourceLocation("minecraft:" + path.split("\\.")[1]));
                        entry.setEventSound("PLACE");
                        if (block != Blocks.AIR) {
                            entry.setIdentifier(blockStateToString(block.defaultBlockState()));
                        } else {
                            System.out.println("Unable to auto map PLACE sound: " + path);
                            entry.setIdentifier("MANUALMAP");
                        }
                    }
                }

                if (entry.isLevelEvent()) {
                    try {
                        LevelEvent.valueOf(entry.getEventSound());
                    } catch (Exception ignored) {
                        System.out.println("Invalid LevelEvent " + entry.getEventSound() + " for java sound " + path);
                    }
                } else if (!isBlank(entry.getEventSound())) {
                    try {
                        org.cloudburstmc.protocol.bedrock.data.SoundEvent.valueOf(entry.getEventSound());
                    } catch (Exception ignored) {
                        System.out.println("Invalid SoundEvent " + entry.getEventSound() + " for java sound " + path);
                    }
                }

                if (isBlank(entry.getPlaySound()) && isBlank(entry.getEventSound())) {
                    System.out.println("No mapping for java sound: " + path);
                }

                JsonObject object = (JsonObject) GSON.toJsonTree(entry);
                /*
                if (isBlank(playSound)) {
                    object.remove("playsound_mapping");
                } // this causes a lot of diff, only apply it once everything is fixed
                if (isBlank(eventSound)) {
                    object.remove("bedrock_mapping");
                }
                 */
                if (entry.getExtraData() <= 0 && !path.equals("block.note_block.harp")) {
                    object.remove("extra_data");
                }
                if (isBlank(entry.getIdentifier())) {
                    object.remove("identifier");
                }
                if (!entry.isLevelEvent()) {
                    object.remove("level_event");
                }
                rootObject.add(path, object);
            }

            FileWriter writer = new FileWriter(mappings);
            builder.create().toJson(rootObject, writer);
            writer.close();
            fileSystem.close();
            System.out.println("Finished sound writing process!");
            System.out.println("Some PLACE identifiers need to be manually mapped, please search for MANUALMAP in sounds.json, if there are no occurrences you do not need to do anything.");
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    private boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }

    private boolean updatePlaySound(SoundEntry entry, String javaIdentifier, Set<String> bedrockSounds) {
        if (bedrockSounds.contains(javaIdentifier)) {
            entry.setPlaySound(javaIdentifier);
            return true;
        }

        String identifier = javaIdentifier;
        if (identifier.startsWith("block.note_block")) {
            identifier = "note" + identifier.substring(identifier.lastIndexOf('.'));
        } else if (identifier.startsWith("block.")) {
            identifier = identifier.replace("weeping_vines", "roots");
            identifier = identifier.replace("block.gilded_blackstone.", "block.stone.");
            identifier = identifier.replace("block.metal.", "block.stone.");
            identifier = identifier.replace("block.vine", "block.vines");
            identifier = identifier.replace("small_dripleaf", "big_dripleaf");
            identifier = identifier.replace("rooted_dirt", "dirt_with_roots");
            identifier = identifier.replace("nether_ore", "nether_gold_ore"); // ??? mojang
            identifier = identifier.replace("netherite_block", "netherite");
            identifier = identifier.replace("polished_deepslate", "deepslate");
            identifier = identifier.replace("deepslate_tiles", "deepslate_bricks");
            identifier = identifier.replace("flowering_azalea", "azalea");
            identifier = identifier.replace("frogspawn", "frog_spawn");
            identifier = identifier.replace("moss_carpet", "moss");
            identifier = identifier.replace("nether_bricks", "nether_brick");
            identifier = identifier.replace("wart_block", "nether_wart");

            identifier = identifier.substring("block.".length());
            String[] parts = identifier.split("\\.");
            if (parts.length > 1) {
                identifier = parts[1] + "." + parts[0];
            }
        } else if (identifier.startsWith("item.brush")) {
            String[] parts = identifier.split("\\.");
            identifier = "brush.suspicious_" + parts[3];
        } else if (identifier.startsWith("music.")) {
            // a lot of the bedrock names use "game" instead of overworld or nether
            String[] parts = identifier.split("\\.", 3);
            if (parts.length == 3) {
                identifier = "music.game." + parts[2];
            }
        } else if (identifier.startsWith("entity.")) {
            identifier = identifier.replace("entity.", "mob.");

            if (identifier.contains("donkey")) {
                identifier = identifier.replace("donkey", "horse.donkey");
            } else if (identifier.contains("goat.screaming")) {
                identifier = identifier.replace(".screaming", "");

                String screamer = identifier + ".screamer";
                if (bedrockSounds.contains(screamer)) {
                    identifier = screamer; // specific screamer sound
                }
                // otherwise uses normal goat sound
            }
        } else {
            identifier = identifier.replace("item.armor", "armor");
        }

        if (bedrockSounds.contains(identifier)) {
            entry.setPlaySound(identifier);
            return true;
        }
        return false;
    }

    public void generateBiomes() {
        try {
            File mappings = new File("mappings/biomes.json");
            if (!mappings.exists()) {
                System.out.println("Could not find mappings submodule! Did you clone them?");
                return;
            }

            Map<String, BiomeEntry> biomesMap = new TreeMap<>();
            try {
                Type mapType = new TypeToken<Map<String, BiomeEntry>>() {}.getType();
                Map<String, BiomeEntry> existingBiomes = GSON.fromJson(new FileReader(mappings), mapType);
                if (existingBiomes != null) {
                    biomesMap.putAll(existingBiomes);
                }
            } catch (FileNotFoundException ex) {
                ex.printStackTrace();
                return;
            }

            File biomeIdMap = new File("palettes/biome_id_map.json");
            if (!biomeIdMap.exists()) {
                System.out.println("Biome ID map doesn't exist!!!");
                return;
            }

            // Used to know if a biome is valid or not for Bedrock
            JsonObject bedrockBiomes = JsonParser.parseReader(new FileReader(biomeIdMap)).getAsJsonObject();

            Set<ResourceLocation> javaBiomes = VanillaRegistries.createLookup().lookup(Registries.BIOME).get()
                .listElements().map(ref -> ref.key().location()).collect(Collectors.toSet());

            // Check for outdated fallback biomes
            Set<String> biomeNames = javaBiomes.stream().map(ResourceLocation::getPath).collect(Collectors.toSet());
            for (String javaBiome : FALLBACK_BIOMES.keySet()) {
                if (!biomeNames.contains(javaBiome)) {
                    System.out.println("Fallback " + javaBiome + " -> " + FALLBACK_BIOMES.get(javaBiome) + " will never be used because the java biome doesn't actually exist.");
                }
            }

            int i = -1;
            for (ResourceLocation javaBiome : javaBiomes) {
                i++;
                JsonElement biomeId = bedrockBiomes.get(javaBiome.getPath());
                if (biomeId == null) {
                    String replacementBiome = FALLBACK_BIOMES.get(javaBiome.getPath());
                    if (replacementBiome != null) {
                        biomeId = bedrockBiomes.get(replacementBiome);
                        if (biomeId == null) {
                            throw new IllegalStateException("Biome ID was null when explicitly replaced for " + replacementBiome);
                        }
                    } else {
                        System.out.println("Replacement biome required for " + javaBiome.getPath() + " (ID: " + i + ")");
                        continue;
                    }
                }

                biomesMap.put(javaBiome.toString(), new BiomeEntry(biomeId.getAsInt()));
            }

            GsonBuilder builder = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping();
            FileWriter writer = new FileWriter(mappings);
            builder.create().toJson(biomesMap, writer);
            writer.close();
            System.out.println("Finished biome writing process!");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void generateMapColors() {
        List<Color> mapColors = new ArrayList<>();
        for (MapColor color : MapColor.MATERIAL_COLORS) {
            if (color == null) {
                continue;
            }

            for (MapColor.Brightness brightness : MapColor.Brightness.values()) {
                int rgb = color.calculateRGBColor(brightness);
                mapColors.add(new Color(rgb, true));
            }
        }

        StringBuilder finalOutput = new StringBuilder();
        for (int i = 0; i < mapColors.size(); i++) {
            Color color = mapColors.get(i);
            finalOutput.append("COLOR_").append(i).append("(").append(color.getRed()).append(", ").append(color.getGreen()).append(", ").append(color.getBlue()).append("),\n");
        }

        // Remap the empty colors
        finalOutput = new StringBuilder(finalOutput.toString().replaceAll("\\(0, 0, 0\\)", "(-1, -1, -1)"));

        // Fix the end
        finalOutput = new StringBuilder(finalOutput.substring(0, finalOutput.length() - 2) + ";");

        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter("./map_colors.txt"));
            writer.write(finalOutput.toString());
            writer.close();
            System.out.println("Finished map color writing process!");
        } catch (IOException e) {
            System.out.println("Failed to write map_colors.txt!");
            e.printStackTrace();
        }
    }

    public void generateEnchantments() {
        try {
            Map<String, EnchantmentEntry> enchantmentMap = new HashMap<>();
            for (int id = 0; id < BuiltInRegistries.ENCHANTMENT.size(); id++) {
                Enchantment enchantment = BuiltInRegistries.ENCHANTMENT.byId(id);

                String rarity = enchantment.getRarity().toString().toLowerCase();
                int maxLevel = enchantment.getMaxLevel();
                List<String> incompatibleEnchantments = new ArrayList<>();
                List<String> validItems = new ArrayList<>();
                for (int id2 = 0; id2 < BuiltInRegistries.ENCHANTMENT.size(); id2++) {
                    Enchantment enchantment2 = BuiltInRegistries.ENCHANTMENT.byId(id2);
                    if (enchantment != enchantment2 && !enchantment.isCompatibleWith(enchantment2)) {
                        incompatibleEnchantments.add(BuiltInRegistries.ENCHANTMENT.getKey(enchantment2).toString());
                    }
                }
                if (incompatibleEnchantments.isEmpty()) {
                    incompatibleEnchantments = null;
                }
                // Super inefficient, but I don't think there is a better way
                for (int i = 0; i < BuiltInRegistries.ITEM.size(); i++) {
                    Item value = BuiltInRegistries.ITEM.byId(i);
                    ResourceLocation key = BuiltInRegistries.ITEM.getKey(value);
                    ItemStack itemStack = new ItemStack(value);
                    if (enchantment.canEnchant(itemStack)) {
                        validItems.add(key.getNamespace() + ":" + key.getPath());
                    }
                }
                enchantmentMap.put(BuiltInRegistries.ENCHANTMENT.getKey(enchantment).toString(), new EnchantmentEntry(rarity, maxLevel, incompatibleEnchantments, validItems));
            }

            GsonBuilder builder = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping();
            File mappings = new File("mappings/enchantments.json");
            FileWriter writer = new FileWriter(mappings);
            builder.create().toJson(enchantmentMap, writer);
            writer.close();
            System.out.println("Finished enchantment writing process!");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void generateParticles() {
        File mappings = new File("mappings/particles.json");
        if (!mappings.exists()) {
            System.out.println("Could not find mappings submodule! Did you clone them?");
            return;
        }

        Map<String, ParticleEntry> particles;
        try {
            Type mapType = new TypeToken<Map<String, ParticleEntry>>() {}.getType();
            particles = GSON.fromJson(new FileReader(mappings), mapType);
        } catch (FileNotFoundException ex) {
            ex.printStackTrace();
            return;
        }

        List<String> validParticleIds = new ArrayList<>();
        try (FileSystem fileSystem = FileSystems.newFileSystem(Paths.get("bedrockresourcepack.zip"))) {
            Path particlesPath = fileSystem.getPath("particles");
            fileSystem.provider().newDirectoryStream(particlesPath, (entry) -> true)
                    .forEach((jsonPath) -> {
                        try {
                            JsonElement json = JsonParser.parseReader(new InputStreamReader(fileSystem.provider().newInputStream(jsonPath)));
                            String bedrockId = json.getAsJsonObject()
                                    .getAsJsonObject("particle_effect")
                                    .getAsJsonObject("description")
                                    .get("identifier")
                                    .getAsString();
                            validParticleIds.add(bedrockId);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    });
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        // We don't need to worry about registry order since MCProtocolLib will take that of that
        Map<String, ParticleEntry> newParticles = new TreeMap<>();

        for (Map.Entry<String, ParticleEntry> entry : particles.entrySet()) {
            ResourceLocation location = new ResourceLocation("minecraft", entry.getKey().toLowerCase(Locale.ROOT));
            if (BuiltInRegistries.PARTICLE_TYPE.get(location) == null) {
                System.out.println("Particle of type " + entry.getKey() + " does not exist in this jar! It will be removed.");
            }
        }

        for (Map.Entry<ResourceKey<ParticleType<?>>, ParticleType<?>> entry : BuiltInRegistries.PARTICLE_TYPE.entrySet()) {
            String enumName = entry.getKey().location().getPath().toUpperCase(Locale.ROOT);
            ParticleEntry geyserParticle = particles.computeIfAbsent(enumName, ($) -> new ParticleEntry());

            if (geyserParticle.cloudburstLevelEventType == null) {
                if (isBedrockParticleType(enumName)) {
                    geyserParticle.cloudburstLevelEventType = enumName; // parity
                }
            }

            if (geyserParticle.cloudburstLevelEventType != null) {
                if (!isBedrockParticleType(geyserParticle.cloudburstLevelEventType)) {
                    System.out.println("Particle type " + geyserParticle.cloudburstLevelEventType + " does not exist in the Cloudburst Protocol!");
                    geyserParticle.cloudburstLevelEventType = null;
                }
            }
            if (geyserParticle.bedrockId != null && !geyserParticle.bedrockId.startsWith("geyseropt:")) {
                // Ignore Geyser prefixes as these won't be found in the Bedrock resource pack
                if (!validParticleIds.contains(geyserParticle.bedrockId)) {
                    System.out.println("Bedrock particle ID " + geyserParticle.bedrockId + " not found in resource pack.");
                }
            }
            if (geyserParticle.cloudburstLevelEventType == null && geyserParticle.bedrockId == null) {
                System.out.println("No Bedrock particle mapped for " + enumName);
                if (validParticleIds.contains(entry.getKey().location().toString())) {
                    System.out.println("But the Bedrock resource pack contains a particle with the ID " + entry.getKey().location());
                }
            }
            newParticles.put(enumName, geyserParticle);
        }

        try {
            GsonBuilder builder = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping();
            JsonWriter writer = new JsonWriter(new FileWriter(mappings));
            writer.setIndent("\t"); // Tabs just to keep the diff nice for older mappings
            builder.create().toJson(newParticles, Map.class, writer);
            writer.close();
            System.out.println("Finished particle writing process!");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private boolean isBedrockParticleType(String enumName) {
        try {
            // Check if we have a particle type mapping
            org.cloudburstmc.protocol.bedrock.data.ParticleType.valueOf(enumName);
        } catch (IllegalArgumentException ignored) {
            // No particle type; try level event
            try {
                LevelEvent.valueOf(enumName);
            } catch (IllegalArgumentException ignoredAgain) {
                return false;
            }
        }
        return true;
    }

    public void generateInteractionData() {
        ClientLevel mockClientLevel = mock(ClientLevel.class);
        mockClientLevel.isClientSide = true;
        mockClientLevel.random = RandomSource.create(); // Used by cave_vines and doors
        when(mockClientLevel.getRandom()).thenReturn(mockClientLevel.random);

        when(mockClientLevel.getBlockState(any())).thenReturn(Blocks.AIR.defaultBlockState());

        Abilities abilities = new Abilities();
        abilities.mayBuild = true;

        LocalPlayer mockPlayer = mock(LocalPlayer.class);

        // Used by bee_hive
        mockPlayer.level = mockClientLevel;
        mockPlayer.position = Vec3.ZERO;
        when(mockPlayer.getInventory()).thenReturn(new Inventory(mockPlayer));

        AtomicBoolean requiresAbilities = new AtomicBoolean(false);
        when(mockPlayer.getAbilities()).then(invocationOnMock -> {
            requiresAbilities.set(true);
            return abilities;
        });

        when(mockPlayer.getDirection()).thenReturn(Direction.UP); // Used by fence_gates

        AtomicReference<ItemStack> item = new AtomicReference<>(ItemStack.EMPTY);
        AtomicBoolean requiresItem = new AtomicBoolean(false);
        when(mockPlayer.getItemInHand(InteractionHand.MAIN_HAND)).then(invocationOnMock -> {
            requiresItem.set(true);
            return item.get();
        });
        when(mockPlayer.getItemInHand(InteractionHand.OFF_HAND)).thenReturn(ItemStack.EMPTY);

        when(mockClientLevel.enabledFeatures()).thenReturn(FeatureFlags.DEFAULT_FLAGS);

        BlockHitResult blockHitResult = new BlockHitResult(Vec3.ZERO, Direction.DOWN, BlockPos.ZERO, true);

        List<String> alwaysConsume = new ArrayList<>();
        List<String> requiresMayBuild = new ArrayList<>();

        for (BlockState state : getAllStates()) {
            try {
                if (state.getBlock() == Blocks.REDSTONE_WIRE) {
                    continue; // Interactions with Redstone wire depend on the wires around it
                } else if (state.getBlock() == Blocks.BELL) {
                    continue; // Interactions with Bells depend on the HitResult
                } else if (state.getBlock() == Blocks.LIGHT) {
                    continue; // Can't interact with light blocks without holding a light item
                } else if (state.getBlock() == Blocks.CAMPFIRE || state.getBlock() == Blocks.SOUL_CAMPFIRE) {
                    continue; // Interactions with campfires depends on campfire recipes
                } else if (state.getBlock() instanceof FlowerPotBlock) {
                    alwaysConsume.add(blockStateToString(state)); // Contains checks for item, but will always consume the action
                    continue;
                } else if (state.getBlock() == Blocks.DRAGON_EGG) {
                    alwaysConsume.add(blockStateToString(state)); // Teleports and will always consume the action
                    continue;
                } else if (state.getBlock() == Blocks.CAKE) {
                    continue; // Depends on the player's hunger level
                } else if (state.getBlock() instanceof SignBlock) {
                    alwaysConsume.add(blockStateToString(state)); // Contains checks for item, but will always consume the action
                    continue;
                }

                requiresAbilities.set(false);
                abilities.mayBuild = true;

                requiresItem.set(false);
                item.set(ItemStack.EMPTY);

                if (state.getBlock() instanceof BaseEntityBlock baseEntityBlock) {
                    when(mockClientLevel.getBlockEntity(BlockPos.ZERO)).thenReturn(baseEntityBlock.newBlockEntity(BlockPos.ZERO, state));
                } else {
                    when(mockClientLevel.getBlockEntity(BlockPos.ZERO)).thenReturn(null);
                }
                when(mockClientLevel.getBlockState(new BlockPos(0, 0, 0))).thenReturn(state);

                InteractionResult result = state.use(mockClientLevel, mockPlayer, InteractionHand.MAIN_HAND, blockHitResult);
                if (!requiresItem.get()) {
                    if (result.consumesAction() && requiresAbilities.get()) {
                        abilities.mayBuild = false;
                        InteractionResult result2 = state.use(mockClientLevel, mockPlayer, InteractionHand.MAIN_HAND, blockHitResult);
                        if (result != result2) {
                            requiresMayBuild.add(blockStateToString(state));
                        }
                    } else if (result.consumesAction()) {
                        alwaysConsume.add(blockStateToString(state));
                    }
                }
            } catch (Throwable e) {
                // Ignore; this means the block has extended behavior we have to implement manually
                System.out.println("Failed to test interactions for " + blockStateToString(state) + " due to");
                e.printStackTrace(System.out);
            }
        }

        File mappings = new File("mappings/interactions.json");
        if (!mappings.exists()) {
            System.out.println("Could not find mappings submodule! Did you clone them?");
            return;
        }
        try {
            GsonBuilder builder = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping();
            JsonWriter writer = new JsonWriter(new FileWriter(mappings));
            writer.setIndent("\t");
            builder.create().toJson(new InteractionData(alwaysConsume, requiresMayBuild), InteractionData.class, writer);
            writer.close();
            System.out.println("Finished interaction writing process!");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unchecked")
    private static Optional<BlockBehaviour.OffsetFunction> getOffsetFunction(BlockState block) {
        try {
            Field offsetFunctionField = BlockBehaviour.BlockStateBase.class.getDeclaredField("offsetFunction");
            offsetFunctionField.setAccessible(true);

            return (Optional<BlockBehaviour.OffsetFunction>) offsetFunctionField.get(block);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public JsonObject getRemapBlock(BlockState state, String identifier) {
        JsonObject object = new JsonObject();
        BlockEntry blockEntry = BLOCK_ENTRIES.get(identifier);
        String trimmedIdentifier = identifier.split("\\[")[0];

        String bedrockIdentifier;
        if (BLOCK_OVERRIDES.containsKey(trimmedIdentifier)) {
            // handle any special cases first
            bedrockIdentifier = BLOCK_OVERRIDES.get(trimmedIdentifier);
        } else if (trimmedIdentifier.endsWith("_wall") && isSensibleWall(trimmedIdentifier)) {
            // All walls before 1.16 use the same identifier (cobblestone_wall)
            // Reset any existing mapping to cobblestone wall
            bedrockIdentifier = trimmedIdentifier;
        } else if (trimmedIdentifier.endsWith("_wall")) {
            bedrockIdentifier = "minecraft:cobblestone_wall";
        } else if (trimmedIdentifier.equals("minecraft:powered_rail")) {
            bedrockIdentifier = "minecraft:golden_rail";
        } else if (trimmedIdentifier.equals("minecraft:light")) {
            bedrockIdentifier = "minecraft:light_block";
        } else if (trimmedIdentifier.equals("minecraft:dirt_path")) {
            bedrockIdentifier = "minecraft:grass_path";
        } else if (trimmedIdentifier.equals("minecraft:small_dripleaf")) {
            bedrockIdentifier = "minecraft:small_dripleaf_block";
        } else if (trimmedIdentifier.equals("minecraft:big_dripleaf_stem")) {
            // Includes the head and stem
            bedrockIdentifier = "minecraft:big_dripleaf";
        } else if (trimmedIdentifier.equals("minecraft:flowering_azalea_leaves")) {
            bedrockIdentifier = "minecraft:azalea_leaves_flowered";
        } else if (trimmedIdentifier.equals("minecraft:rooted_dirt")) {
            bedrockIdentifier = "minecraft:dirt_with_roots";
        } else if (trimmedIdentifier.contains("powder_snow_cauldron") || trimmedIdentifier.contains("water_cauldron")) {
            bedrockIdentifier = "minecraft:cauldron";
        } else if (trimmedIdentifier.equals("minecraft:waxed_copper_block")) {
            bedrockIdentifier = "minecraft:waxed_copper";
        } else if (trimmedIdentifier.contains("candle")) {
            // Resetting old identifiers
            bedrockIdentifier = trimmedIdentifier;
        } else if (identifier.equals("minecraft:deepslate_redstone_ore[lit=true]")) {
            bedrockIdentifier = "minecraft:lit_deepslate_redstone_ore";
        } else if (trimmedIdentifier.endsWith("_slab") && identifier.contains("type=double")) {
            // Fixes 1.16 double slabs
            if (blockEntry != null) {
                if (blockEntry.getBedrockIdentifier().contains("double") && !blockEntry.getBedrockIdentifier().contains("copper")) {
                    bedrockIdentifier = blockEntry.getBedrockIdentifier();
                } else {
                    bedrockIdentifier = formatDoubleSlab(trimmedIdentifier);
                }
            } else {
                bedrockIdentifier = formatDoubleSlab(trimmedIdentifier);
            }
        } else if (trimmedIdentifier.endsWith("_leaves")) {
            if (trimmedIdentifier.contains(":oak") || trimmedIdentifier.contains("spruce") || trimmedIdentifier.contains("birch") || trimmedIdentifier.contains("jungle")) {
                bedrockIdentifier = "minecraft:leaves";
            } else if (trimmedIdentifier.contains("acacia") || trimmedIdentifier.contains("dark_oak")) {
                bedrockIdentifier = "minecraft:leaves2";
            } else {
                // Default to trimmed identifier, or the existing identifier
                bedrockIdentifier = blockEntry != null ? blockEntry.getBedrockIdentifier() : trimmedIdentifier;
            }
        } else if (trimmedIdentifier.equals("minecraft:mangrove_sign")) {
            bedrockIdentifier = "minecraft:mangrove_standing_sign";
        } else if (trimmedIdentifier.equals("minecraft:tripwire")) {
            bedrockIdentifier = "minecraft:trip_wire";
        } else if (trimmedIdentifier.startsWith("minecraft:potted")) {
            // Pots are block entities on Bedrock
            bedrockIdentifier = "minecraft:flower_pot";
        } else if (trimmedIdentifier.endsWith("concrete_powder")) {
            bedrockIdentifier = "minecraft:concrete_powder";
        } else if (trimmedIdentifier.endsWith("piston_head")) {
            if (identifier.contains("type=sticky")) {
                bedrockIdentifier = "minecraft:sticky_piston_arm_collision";
            } else {
                bedrockIdentifier = "minecraft:piston_arm_collision";
            }
        } else if (trimmedIdentifier.endsWith("moving_piston")) {
            bedrockIdentifier = "minecraft:moving_block";
        } else if (trimmedIdentifier.endsWith("note_block")) {
            bedrockIdentifier = "minecraft:noteblock";
        } else if (trimmedIdentifier.endsWith("_wall_hanging_sign")) {
            // "wall hanging" signs do not exist on BE. they are just hanging signs.
            bedrockIdentifier = trimmedIdentifier.replace("_wall", "");
        } else if (trimmedIdentifier.endsWith("_hanging_sign")) {
            bedrockIdentifier = trimmedIdentifier;
        } else if (isSkull(trimmedIdentifier)) {
            bedrockIdentifier = "minecraft:skull";
        } else {
            // Default to trimmed identifier, or the existing identifier
            bedrockIdentifier = blockEntry != null ? blockEntry.getBedrockIdentifier() : trimmedIdentifier;
        }

        if (bedrockIdentifier.contains(":stone_slab") || bedrockIdentifier.contains(":double_stone_slab")) {
            bedrockIdentifier = bedrockIdentifier.replace("stone_slab", "stone_block_slab");
        }

        object.addProperty("bedrock_identifier", bedrockIdentifier);

        object.addProperty("block_hardness", state.getDestroySpeed(null, null));

        if (state.hasOffsetFunction()) {
            var offsetDataObject = new JsonObject();

            offsetDataObject.addProperty("max_horizontal_offset", state.getBlock().getMaxHorizontalOffset());
            offsetDataObject.addProperty("max_vertical_offset", state.getBlock().getMaxVerticalOffset());

            var offsetFunction = getOffsetFunction(state).orElseThrow();
            var vec = offsetFunction.evaluate(Blocks.GRASS.defaultBlockState(), EmptyBlockGetter.INSTANCE, new BlockPos(1, 2, 3));

            String offsetType;
            if (vec.y == 0.0) {
                offsetType = "XZ";
            } else {
                offsetType = "XYZ";
            }

            offsetDataObject.addProperty("type", offsetType);

            object.add("offset_data", offsetDataObject);
        }

        List<List<Double>> collisionBoxes = Lists.newArrayList();
        try {
            var collisionShape = state.getCollisionShape(null, BlockPos.ZERO);
            var offset = state.getOffset(null, BlockPos.ZERO);
            var reversedOffset = offset.reverse();
            var collisionShapeWithoutOffset = collisionShape.move(reversedOffset.x, reversedOffset.y, reversedOffset.z);

            collisionShapeWithoutOffset.toAabbs().forEach(item -> {
                List<Double> coordinateList = Lists.newArrayList();
                // Convert Box class to an array of coordinates
                // They need to be converted from min/max coordinates to centres and sizes
                coordinateList.add(item.minX + ((item.maxX - item.minX) / 2));
                coordinateList.add(item.minY + ((item.maxY - item.minY) / 2));
                coordinateList.add(item.minZ + ((item.maxZ - item.minZ) / 2));

                coordinateList.add(item.maxX - item.minX);
                coordinateList.add(item.maxY - item.minY);
                coordinateList.add(item.maxZ - item.minZ);

                collisionBoxes.add(coordinateList);
            });
        } catch (NullPointerException e) {
            // Fallback to empty collision when a block getter is needed to calculate it
        }

        if (!COLLISION_LIST.contains(collisionBoxes)) {
            COLLISION_LIST.add(collisionBoxes);
        }
        // This points to the index of the collision in collision.json
        object.addProperty("collision_index", COLLISION_LIST.lastIndexOf(collisionBoxes));

        PushReaction pushReaction = state.getPistonPushReaction();
        if (pushReaction != PushReaction.NORMAL) {
            object.addProperty("piston_behavior", pushReaction.toString().toLowerCase());
        }

        if (state.hasBlockEntity()) {
            object.addProperty("has_block_entity", true);
        }

        try {
            // Ignore water, lava, and fire because players can't pick them
            if (!trimmedIdentifier.equals("minecraft:water") && !trimmedIdentifier.equals("minecraft:lava") && !trimmedIdentifier.equals("minecraft:fire")) {
                Block block = state.getBlock();
                ItemStack pickStack = block.getCloneItemStack(null, null, state);
                String pickStackIdentifier = BuiltInRegistries.ITEM.getKey(pickStack.getItem()).toString();
                if (!pickStackIdentifier.equals(trimmedIdentifier)) {
                    object.addProperty("pick_item", pickStackIdentifier);
                }
            }
        } catch (NullPointerException e) {
            // The block's pick item depends on a block entity.
            // Banners and Shulker Boxes both depend on the block entity.
        }
        object.addProperty("can_break_with_hand", !state.requiresCorrectToolForDrops());
        // Removes nbt tags from identifier
        // Add tool type for blocks that use shears or sword
        if (trimmedIdentifier.contains("_bed")) {
            String woolid = trimmedIdentifier.replace("minecraft:", "");
            woolid = woolid.split("_bed")[0].toUpperCase();
            object.addProperty("bed_color", DyeColor.valueOf(woolid).getId());
        } else if (isSkull(trimmedIdentifier)) {
            if (!trimmedIdentifier.contains("wall")) {
                int rotationId = Integer.parseInt(Objects.requireNonNull(StateMapper.getStateValue(identifier, "rotation")));
                object.addProperty("skull_rotation", rotationId);
            }
            if (trimmedIdentifier.contains("wither_skeleton")) {
                object.addProperty("variation", 1);
            } else if (trimmedIdentifier.contains("skeleton")) {
                object.addProperty("variation", 0);
            } else if (trimmedIdentifier.contains("zombie")) {
                object.addProperty("variation", 2);
            } else if (trimmedIdentifier.contains("player")) {
                object.addProperty("variation", 3);
            } else if (trimmedIdentifier.contains("creeper")) {
                object.addProperty("variation", 4);
            } else if (trimmedIdentifier.contains("dragon")) {
                object.addProperty("variation", 5);
            } else if (trimmedIdentifier.contains("piglin")) {
                object.addProperty("variation", 6);
            }
        } else if (trimmedIdentifier.contains("_banner")) {
            String woolid = trimmedIdentifier.replace("minecraft:", "");
            woolid = woolid.split("_banner")[0].split("_wall")[0].toUpperCase();
            object.addProperty("banner_color", DyeColor.valueOf(woolid).getId());
        } else if (trimmedIdentifier.contains("note_block")) {
            int notepitch = Integer.parseInt(identifier.substring(identifier.indexOf("note=") + 5, identifier.indexOf(",powered")));
            object.addProperty("note_pitch", notepitch);
        } else if (trimmedIdentifier.contains("shulker_box")) {
            object.addProperty("shulker_direction", getDirectionInt(identifier.substring(identifier.indexOf("facing=") + 7, identifier.indexOf("]"))));
        } else if (trimmedIdentifier.contains("chest") && (identifier.contains("type="))) {
            if (identifier.contains("type=left")) {
                object.addProperty("double_chest_position", "left");
            } else if (identifier.contains("type=right")) {
                object.addProperty("double_chest_position", "right");
            }
            if (identifier.contains("north")) {
                object.addProperty("z", false);
            } else if (identifier.contains("south")) {
                object.addProperty("z", true);
            } else if (identifier.contains("east")) {
                object.addProperty("x", true);
            } else if (identifier.contains("west")) {
                object.addProperty("x", false);
            }
        }

        JsonElement bedrockStates = blockEntry != null ? blockEntry.getBedrockStates() : null;
        if (bedrockStates == null) {
            bedrockStates = new JsonObject();
        }

        JsonObject statesObject = bedrockStates.getAsJsonObject();
        if (blockEntry != null && STATES.get(blockEntry.getBedrockIdentifier()) != null) {
            // Prevent ConcurrentModificationException
            List<String> toRemove = new ArrayList<>();
            // Since we now rely on block states being exact after 1.16.100, we need to remove any old states
            for (Map.Entry<String, JsonElement> entry : statesObject.entrySet()) {
                List<String> states = STATES.get(blockEntry.getBedrockIdentifier());
                if (!states.contains(entry.getKey()) &&
                        !entry.getKey().contains("stone_slab_type")) { // Ignore the stone slab types since we ignore them above
                    toRemove.add(entry.getKey());
                }
            }
            for (String key : toRemove) {
                statesObject.remove(key);
            }
        } else if (blockEntry != null) {
            System.out.println("States for " + blockEntry.getBedrockIdentifier() + " not found!");
        } else {
            System.out.println("Block entry for " + blockStateToString(state) + " is null?");
        }
        String[] states = StateMapper.getStates(identifier);
        for (String javaState : states) {
            String key = javaState.split("=")[0];
            if (!this.stateMappers.containsKey(key)) {
                continue;
            }
            Collection<StateMapper<?>> stateMappers = this.stateMappers.get(key);

            stateLoop:
            for (StateMapper<?> stateMapper : stateMappers) {
                String[] blockRegex = stateMapper.getClass().getAnnotation(StateRemapper.class).blockRegex();
                if (blockRegex.length != 0) {
                    for (String regex : blockRegex) {
                        if (!trimmedIdentifier.matches(regex)) {
                            continue stateLoop;
                        }
                    }
                }
                String value = javaState.split("=")[1];
                Pair<String, ?> bedrockState = stateMapper.translateState(identifier, value);
                if (bedrockState.getValue() instanceof Number) {
                    statesObject.addProperty(bedrockState.getKey(), StateMapper.asType(bedrockState, Number.class));
                }
                if (bedrockState.getValue() instanceof Boolean) {
                    statesObject.addProperty(bedrockState.getKey(), StateMapper.asType(bedrockState, Boolean.class));
                }
                if (bedrockState.getValue() instanceof String) {
                    statesObject.addProperty(bedrockState.getKey(), StateMapper.asType(bedrockState, String.class));
                }
            }
        }

        if (trimmedIdentifier.equals("minecraft:glow_lichen") || trimmedIdentifier.equals("minecraft:sculk_vein")) {
            int bitset = 0;
            List<String> statesList = Arrays.asList(states);
            if (statesList.contains("down=true")) {
                bitset |= 1;
            }
            if (statesList.contains("up=true")) {
                bitset |= 1 << 1;
            }
            if (statesList.contains("south=true")) {
                bitset |= 1 << 2;
            }
            if (statesList.contains("west=true")) {
                bitset |= 1 << 3;
            }
            if (statesList.contains("north=true")) {
                bitset |= 1 << 4;
            }
            if (statesList.contains("east=true")) {
                bitset |= 1 << 5;
            }
            statesObject.addProperty("multi_face_direction_bits", bitset);
        }

        else if (trimmedIdentifier.endsWith("_cauldron")) {
            statesObject.addProperty("cauldron_liquid", trimmedIdentifier.replace("minecraft:", "").replace("_cauldron", ""));
            if (trimmedIdentifier.equals("minecraft:lava_cauldron")) {
                // Only one fill level option
                statesObject.addProperty("fill_level", 6);
            }
        }

        else if (trimmedIdentifier.contains("big_dripleaf")) {
            boolean isHead = !trimmedIdentifier.contains("stem");
            statesObject.addProperty("big_dripleaf_head", isHead);
            if (!isHead) {
                statesObject.addProperty("big_dripleaf_tilt", "none");
            }
        } else if (trimmedIdentifier.equals("minecraft:mangrove_wood")) {
            // Didn't seem to do anything
            statesObject.addProperty("stripped_bit", false);
        } else if (trimmedIdentifier.contains("azalea_leaves") || trimmedIdentifier.endsWith("mangrove_leaves")) {
            statesObject.addProperty("update_bit", false);
        }

        String stateIdentifier;
        if (trimmedIdentifier.endsWith("_wall") && !isSensibleWall(trimmedIdentifier)) {
            stateIdentifier = "minecraft:cobblestone_wall";
        } else {
            stateIdentifier = bedrockIdentifier;
        }

        if (bedrockIdentifier.startsWith("minecraft:leaves")) {
            String woodType = trimmedIdentifier.substring(trimmedIdentifier.indexOf(":") + 1, trimmedIdentifier.lastIndexOf("_"));
            if (bedrockIdentifier.endsWith("2")) {
                statesObject.addProperty("new_leaf_type", woodType);
            } else {
                statesObject.addProperty("old_leaf_type", woodType);
            }
            statesObject.addProperty("update_bit", false);
        }

        List<String> stateKeys = STATES.get(stateIdentifier);
        if (stateKeys != null) {
            stateKeys.forEach(key -> {
                if (trimmedIdentifier.contains("minecraft:shulker_box")) return;
                if (!statesObject.has(key)) {
                    statesObject.addProperty(key, "MANUALMAP");
                }
            });
        }

        // No more manual pottable because I'm angry I don't care how bad the list looks
        if (POTTABLE_BLOCK_IDENTIFIERS.contains(trimmedIdentifier)) {
            object.addProperty("pottable", true);
        }

        if (statesObject.entrySet().size() != 0) {
            if (statesObject.has("wall_block_type") && isSensibleWall(trimmedIdentifier)) {
                statesObject.getAsJsonObject().remove("wall_block_type");
            }
            object.add("bedrock_states", statesObject);
        }

        return object;
    }

    public JsonObject getRemapItem(String identifier, Item item, Block block) {
        String trimmedIdentifier = identifier.replace("minecraft:", "");
        JsonObject object = new JsonObject();
        ItemEntry itemEntry = ITEM_ENTRIES.computeIfAbsent(identifier, (key) -> new ItemEntry(key, 0, false));
        // Deal with items that we replace
        String bedrockIdentifier = switch (trimmedIdentifier) {
            case "knowledge_book" -> "book";
            case "tipped_arrow", "spectral_arrow" -> "arrow";
            case "debug_stick" -> "stick";
            case "furnace_minecart" -> "hopper_minecart";
            default -> JAVA_TO_BEDROCK_ITEM_OVERRIDE.getOrDefault(identifier, itemEntry.getBedrockIdentifier()).replace("minecraft:", "");
        };

        if (identifier.endsWith("banner")) { // Don't include banner patterns
            bedrockIdentifier = "banner";
        } else if (identifier.endsWith("bed")) {
            bedrockIdentifier = "bed";
        } else if (identifier.endsWith("_skull") || identifier.endsWith("_head")) {
            bedrockIdentifier = "skull";
        } else if (identifier.endsWith("_shulker_box")) {
            // Colored shulker boxes only
            bedrockIdentifier = "shulker_box";
        }

        if (bedrockIdentifier.startsWith("stone_slab") || bedrockIdentifier.startsWith("double_stone_slab")) {
            bedrockIdentifier = bedrockIdentifier.replace("stone_slab", "stone_block_slab");
        }
        if (bedrockIdentifier.startsWith("double_stone_block_slab")) {
            bedrockIdentifier = bedrockIdentifier.replace("double_stone_block_slab", "stone_block_slab");
        }
        object.addProperty("bedrock_identifier", "minecraft:" + bedrockIdentifier);

        if (!VALID_BEDROCK_ITEMS.contains("minecraft:" + bedrockIdentifier)) {
            System.out.println(bedrockIdentifier + " not found in Bedrock runtime item states!");
        }

        boolean isBlock = block != Blocks.AIR;
        object.addProperty("bedrock_data", isBlock ? itemEntry.getBedrockData() : 0);
        if (isBlock) {
            int firstStateId = -1;
            int lastStateId = -1;
            for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
                if (state.getBlock() == block) {
                    int stateId = Block.getId(state);
                    if (firstStateId == -1) {
                        firstStateId = stateId;
                    }
                    lastStateId = stateId;
                }
            }
            object.addProperty("firstBlockRuntimeId", firstStateId);
            if (firstStateId != lastStateId) {
                object.addProperty("lastBlockRuntimeId", lastStateId);
            }
        }
        String[] toolTypes = {"sword", "shovel", "pickaxe", "axe", "shears", "hoe"};
        String[] identifierSplit = identifier.split(":")[1].split("_");
        if (identifierSplit.length > 1) {
            Optional<String> optToolType = Arrays.stream(toolTypes).parallel().filter(identifierSplit[1]::equals).findAny();
            if (optToolType.isPresent()) {
                object.addProperty("tool_type", optToolType.get());
                object.addProperty("tool_tier", identifierSplit[0]);
            }
        } else {
            Optional<String> optToolType = Arrays.stream(toolTypes).parallel().filter(identifierSplit[0]::equals).findAny();
            optToolType.ifPresent(s -> object.addProperty("tool_type", s));
        }
        String[] armorTypes = {"helmet", "leggings", "chestplate", "boots"};
        if (identifierSplit.length > 1) {
            Optional<String> optToolType = Arrays.stream(armorTypes).parallel().filter(identifierSplit[1]::equals).findAny();
            if (optToolType.isPresent()) {
                object.addProperty("armor_type", optToolType.get());
            }
        } else {
            Optional<String> optToolType = Arrays.stream(armorTypes).parallel().filter(identifierSplit[0]::equals).findAny();
            optToolType.ifPresent(s -> object.addProperty("armor_type", s));
        }
        if (item.getMaxDamage() > 0) {
            Ingredient repairIngredient = null;
            JsonArray repairMaterials = new JsonArray();
            // Some repair ingredients use item tags which are not loaded
            if (item instanceof ArmorItem armorItem) {
                repairIngredient = armorItem.getMaterial().getRepairIngredient();
                object.addProperty("protection_value", armorItem.getDefense());
            } else if (item instanceof ElytraItem) {
                repairIngredient = Ingredient.of(Items.PHANTOM_MEMBRANE);
            } else if (item instanceof TieredItem tieredItem) {
                if (tieredItem.getTier() == Tiers.WOOD) {
                    repairMaterials = ALL_PLANKS;
                } else if (tieredItem.getTier() == Tiers.STONE) {
                    repairMaterials.add("minecraft:cobblestone");
                    repairMaterials.add("minecraft:cobbled_deepslate");
                    repairMaterials.add("minecraft:blackstone"); // JE only https://bugs.mojang.com/browse/MCPE-71859
                } else {
                    repairIngredient = tieredItem.getTier().getRepairIngredient();
                }
            } else if (item instanceof ShieldItem) {
                repairMaterials = ALL_PLANKS;
            }
            if (repairIngredient != null) {
                for (ItemStack repairItem : repairIngredient.getItems()) {
                    repairMaterials.add(BuiltInRegistries.ITEM.getKey(repairItem.getItem()).toString());
                }
            }
            if (repairMaterials.size() > 0) {
                object.add("repair_materials", repairMaterials);
            }
        }

        if (item instanceof SpawnEggItem || item instanceof MinecartItem || item instanceof FireworkRocketItem || item instanceof BoatItem) {
            object.addProperty("is_entity_placer", true);
        }

        if (item.isEdible()) {
            object.addProperty("is_edible", true);
        }

        return object;
    }

    public List<BlockState> getAllStates() {
        List<BlockState> states = new ArrayList<>();
        BuiltInRegistries.BLOCK.forEach(block -> states.addAll(block.getStateDefinition().getPossibleStates()));
        return states.stream().sorted(Comparator.comparingInt(Block::getId)).collect(Collectors.toList());
    }

    private String blockStateToString(BlockState blockState) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(BuiltInRegistries.BLOCK.getKey(blockState.getBlock()));
        if (!blockState.getValues().isEmpty()) {
            stringBuilder.append('[');
            stringBuilder.append(blockState.getValues().entrySet().stream().map(PROPERTY_MAP_PRINTER).collect(Collectors.joining(",")));
            stringBuilder.append(']');
        }
        return stringBuilder.toString();
    }

    private static final Function<Map.Entry<Property<?>, Comparable<?>>, String> PROPERTY_MAP_PRINTER = new Function<>() {

        public String apply(Map.Entry<Property<?>, Comparable<?>> entry) {
            if (entry == null) {
                return "<NULL>";
            } else {
                Property<?> lv = entry.getKey();
                return lv.getName() + "=" + this.nameValue(lv, entry.getValue());
            }
        }

        private <T extends Comparable<T>> String nameValue(Property<T> arg, Comparable<?> comparable) {
            return arg.getName((T) comparable);
        }
    };

    /**
     * Converts a Java edition direction string to an byte for Bedrock edition
     * Designed for Shulker boxes, may work for other things
     *
     * @param direction The direction string
     * @return Converted direction byte
     */
    private static byte getDirectionInt(String direction) {
        return (byte) switch (direction) {
            case "down" -> 0;
            case "north" -> 2;
            case "south" -> 3;
            case "west" -> 4;
            case "east" -> 5;
            default -> 1;
        };

    }

    /**
     * @return true if this wall can be treated normally and not stupidly
     */
    private static boolean isSensibleWall(String identifier) {
        return identifier.contains("blackstone") || identifier.contains("deepslate") || identifier.contains("mud_brick");
    }

    private static boolean isSkull(String identifier) {
        return identifier.contains("skull") || (identifier.contains("head") && !identifier.contains("piston"));
    }

    /**
     * @return the correct double slab identifier for Bedrock
     */
    private static String formatDoubleSlab(String identifier) {
        if (identifier.contains("double")) {
            return identifier;
        }

        if (identifier.contains("cut_copper")) {
            return identifier.replace("cut", "double_cut");
        }
        return identifier.replace("_slab", "_double_slab");
    }
}
