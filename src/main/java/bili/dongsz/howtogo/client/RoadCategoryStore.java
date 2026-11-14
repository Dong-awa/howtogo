package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.GamePaths;
import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.road.ColorHex;
import bili.dongsz.howtogo.road.RoadCategory;
import bili.dongsz.howtogo.road.RoadClass;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The player's own road categories: which exist, their names, and their sign colours.
 * The built-in "not classified" one cannot be renamed, recoloured or removed; all others are
 * created and deleted here. A road keeps only the category id, so renaming repaints every road
 * without touching the save.
 * File: config/howtogo/road-categories.json, read lazily and written on every change.
 */
public final class RoadCategoryStore {

    private static final String FILE_NAME = "road-categories.json";
    private static final int FORMAT_VERSION = 2;

    /** What the built-in category is called; neither name nor colour is the player's to change. */
    private static final String NONE_NAME_KEY = "howtogo.road_category.none";
    private static final int NONE_COLOR = 0xFF7A7F87;
    /** The name a freshly added category starts with; the player renames it in the editor. */
    private static final String NEW_NAME_KEY = "howtogo.road_category.new";

    /**
     * Colours a new category is given in turn, and the presets the picker offers.
     * Road-sign colours, so a few categories stay distinguishable; the picker accepts any colour.
     */
    public static final int[] PALETTE = {
            0xFFC0392B, 0xFFE8A33D, 0xFF2E86C1, 0xFF27AE60, 0xFF8E44AD, 0xFFD35400,
            0xFF16A085, 0xFF1F6F8B, 0xFFC8A24A, 0xFF7F8C8D,
    };

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /** The player-made categories of each class, in the order they were made. */
    private static final Map<RoadClass, List<Definition>> categories =
            new EnumMap<>(RoadClass.class);

    /** The next id each class will hand out; persisted so a deleted id is never reused. */
    private static final Map<RoadClass, Integer> nextIds = new EnumMap<>(RoadClass.class);

    private static boolean loaded;

    private RoadCategoryStore() {
    }

    /** One player-made category: id and appearance, separate from RoadCategory and joined by id. */
    private record Definition(String id, String name, int color) {
    }


    /** The categories of one class, the built-in "not classified" one first. */
    public static List<RoadCategory> ofClass(RoadClass roadClass) {
        ensureLoaded();
        List<RoadCategory> result = new ArrayList<>();
        if (roadClass == null) {
            return result;
        }
        result.add(RoadCategory.none(roadClass));
        for (Definition definition : definitions(roadClass)) {
            result.add(RoadCategory.of(roadClass, definition.id()));
        }
        return result;
    }

    /** The built-in "not classified" category of a class. */
    public static RoadCategory noneOf(RoadClass roadClass) {
        return RoadCategory.none(roadClass == null ? RoadClass.ROAD : roadClass);
    }

    /** Whether this category exists; a road may carry a deleted id, treated as unclassified. */
    public static boolean has(RoadCategory category) {
        if (category == null) {
            return false;
        }
        if (category.isNone()) {
            return true;
        }
        ensureLoaded();
        for (Definition definition : definitions(category.roadClass())) {
            if (definition.id().equals(category.id())) {
                return true;
            }
        }
        return false;
    }

    /** The category itself when it exists, else the class's built-in one. */
    public static RoadCategory normalise(RoadCategory category) {
        if (category == null) {
            return noneOf(null);
        }
        return has(category) ? category : noneOf(category.roadClass());
    }

    /** Whether name and colour are the player's to change; false only for the built-in one. */
    public static boolean isEditable(RoadCategory category) {
        return category != null && !category.isNone();
    }

    /** The shown name; built-in translated, player-made with the store default as fallback. */
    public static Component displayName(RoadCategory category) {
        if (category == null || category.isNone()) {
            return Component.translatable(NONE_NAME_KEY);
        }
        ensureLoaded();
        Definition definition = definition(category);
        if (definition == null || definition.name() == null || definition.name().isBlank()) {
            return Component.translatable(NEW_NAME_KEY);
        }
        return Component.literal(definition.name());
    }

    /** The same name as plain text, for drawing into a map label. */
    public static String name(RoadCategory category) {
        return displayName(category).getString();
    }

    /** The sign colour; built-in grey fixed, deleted categories draw in it too. */
    public static int color(RoadCategory category) {
        if (category == null || category.isNone()) {
            return NONE_COLOR;
        }
        ensureLoaded();
        Definition definition = definition(category);
        return definition == null ? NONE_COLOR : definition.color();
    }


    /**
     * Makes a new category for a class and returns it.
     * Starts with the next palette colour and a placeholder name, written at once; the per-class id
     * counter is never reset, so a deleted id is never reused.
     */
    public static RoadCategory add(RoadClass roadClass) {
        ensureLoaded();
        RoadClass resolvedClass = roadClass == null ? RoadClass.ROAD : roadClass;
        int ordinal = nextIds.merge(resolvedClass, 1, Integer::sum) - 1;
        String id = "c" + ordinal;
        List<Definition> definitions = definitions(resolvedClass);
        int color = PALETTE[(definitions.size() + ordinal) % PALETTE.length];
        String name = Component.translatable(NEW_NAME_KEY).getString();
        definitions.add(new Definition(id, name, color));
        save();
        return RoadCategory.of(resolvedClass, id);
    }

    /**
     * Deletes a player-made category; the built-in one answers false.
     * Roads carrying the deleted id are the caller's to reset; until then has treats them as
     * unclassified.
     */
    public static boolean remove(RoadCategory category) {
        if (!isEditable(category)) {
            return false;
        }
        ensureLoaded();
        List<Definition> definitions = definitions(category.roadClass());
        boolean removed = definitions.removeIf(definition -> definition.id().equals(category.id()));
        if (removed) {
            save();
        }
        return removed;
    }

    /** Renames a player-made category; the built-in one ignores this. */
    public static void setName(RoadCategory category, String name) {
        if (!isEditable(category)) {
            return;
        }
        ensureLoaded();
        List<Definition> definitions = definitions(category.roadClass());
        String trimmed = name == null ? "" : name.trim();
        for (int i = 0; i < definitions.size(); i++) {
            Definition definition = definitions.get(i);
            if (!definition.id().equals(category.id())) {
                continue;
            }
            if (definition.name() != null && definition.name().equals(trimmed)) {
                return;
            }
            definitions.set(i, new Definition(definition.id(), trimmed, definition.color()));
            save();
            return;
        }
    }

    /** Recolours a player-made category; the built-in one ignores this. */
    public static void setColor(RoadCategory category, int argb) {
        if (!isEditable(category)) {
            return;
        }
        ensureLoaded();
        List<Definition> definitions = definitions(category.roadClass());
        for (int i = 0; i < definitions.size(); i++) {
            Definition definition = definitions.get(i);
            if (!definition.id().equals(category.id())) {
                continue;
            }
            if (definition.color() == argb) {
                return;
            }
            definitions.set(i, new Definition(definition.id(), definition.name(), argb));
            save();
            return;
        }
    }


    private static List<Definition> definitions(RoadClass roadClass) {
        if (roadClass == null) {
            return new ArrayList<>();
        }
        return categories.computeIfAbsent(roadClass, ignored -> new ArrayList<>());
    }

    private static Definition definition(RoadCategory category) {
        for (Definition definition : definitions(category.roadClass())) {
            if (definition.id().equals(category.id())) {
                return definition;
            }
        }
        return null;
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path file = file();
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            StoreDto dto = GSON.fromJson(reader, StoreDto.class);
            if (dto == null) {
                return;
            }
            if (dto.categories != null) {
                for (Map.Entry<String, List<EntryDto>> entry : dto.categories.entrySet()) {
                    RoadClass roadClass = classNamed(entry.getKey());
                    if (roadClass == null || entry.getValue() == null) {
                        continue;
                    }
                    List<Definition> definitions = definitions(roadClass);
                    for (EntryDto value : entry.getValue()) {
                        // A row without an id, or with the reserved one, is dropped rather than reported.
                        if (value == null || value.id == null || value.id.isBlank()
                                || RoadCategory.NONE_ID.equalsIgnoreCase(value.id.trim())) {
                            continue;
                        }
                        int color = value.color == null
                                ? PALETTE[definitions.size() % PALETTE.length]
                                : ColorHex.parse(value.color, PALETTE[definitions.size()
                                        % PALETTE.length]);
                        definitions.add(new Definition(value.id.trim(), value.name, color));
                    }
                }
            }
            if (dto.nextId != null) {
                for (Map.Entry<String, Integer> entry : dto.nextId.entrySet()) {
                    RoadClass roadClass = classNamed(entry.getKey());
                    if (roadClass != null && entry.getValue() != null) {
                        nextIds.put(roadClass, Math.max(0, entry.getValue()));
                    }
                }
            }
            // A hand-edited file may have lost the counter: start past every id it holds.
            for (RoadClass roadClass : RoadClass.values()) {
                int next = nextIds.getOrDefault(roadClass, 0);
                for (Definition definition : definitions(roadClass)) {
                    String id = definition.id();
                    if (id.length() > 1 && id.charAt(0) == 'c') {
                        try {
                            next = Math.max(next, Integer.parseInt(id.substring(1)) + 1);
                        } catch (NumberFormatException notANumber) {
                            // A player-made id this mod did not write: keep it, count it as opaque.
                        }
                    }
                }
                nextIds.put(roadClass, next);
            }
        } catch (IOException | JsonSyntaxException e) {
            HowToGo.LOGGER.error("[HowToGo] could not read {}; the categories start empty", file, e);
        }
    }

    private static RoadClass classNamed(String name) {
        if (name == null) {
            return null;
        }
        for (RoadClass roadClass : RoadClass.values()) {
            if (roadClass.name().equalsIgnoreCase(name)) {
                return roadClass;
            }
        }
        return null;
    }

    /** Writes immediately: a value the player just changed, in a small file rewritten whole. */
    private static void save() {
        Path file = file();
        if (file == null) {
            return;
        }
        Path partial = file.resolveSibling(file.getFileName() + ".part");
        try {
            Files.createDirectories(file.getParent());
            StoreDto dto = new StoreDto();
            dto.version = FORMAT_VERSION;
            dto.categories = new LinkedHashMap<>();
            dto.nextId = new LinkedHashMap<>();
            for (RoadClass roadClass : RoadClass.values()) {
                List<Definition> definitions = definitions(roadClass);
                if (definitions.isEmpty()) {
                    continue;
                }
                List<EntryDto> rows = new ArrayList<>(definitions.size());
                for (Definition definition : definitions) {
                    EntryDto row = new EntryDto();
                    row.id = definition.id();
                    row.name = definition.name();
                    row.color = ColorHex.formatArgb(definition.color());
                    rows.add(row);
                }
                dto.categories.put(roadClass.name(), rows);
                dto.nextId.put(roadClass.name(), nextIds.getOrDefault(roadClass, 0));
            }
            try (Writer writer = Files.newBufferedWriter(partial, StandardCharsets.UTF_8)) {
                GSON.toJson(dto, writer);
            }
            replace(partial, file);
        } catch (IOException e) {
            HowToGo.LOGGER.error("[HowToGo] could not write {}", file, e);
        }
    }

    /** Gives partial the target's name, atomically where the filesystem allows it. */
    private static void replace(Path partial, Path file) throws IOException {
        try {
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path file() {
        Path config = GamePaths.configDir();
        return config == null ? null : config.resolve(HowToGo.MODID).resolve(FILE_NAME);
    }

    /** Field names are the on-disk contract, so they are deliberately terse and stable. */
    private static final class StoreDto {
        int version;
        Map<String, List<EntryDto>> categories;
        Map<String, Integer> nextId;
    }

    private static final class EntryDto {
        String id;
        String name;
        /** A colour in ColorHex's spelled form. */
        String color;
    }
}
