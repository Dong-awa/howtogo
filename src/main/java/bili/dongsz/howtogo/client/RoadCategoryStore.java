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
 * The player's own road categories: which exist, what they are called, and what colour their sign is.
 *
 * <h2>What a player owns, and what they do not</h2>
 * Every road class has exactly one category that is not the player's: {@link #noneOf the "not
 * classified" one}, which is what a road is until it is classified. It cannot be renamed, recoloured
 * or removed -- it is the answer every unclassified road gives, so a player could not make it mean
 * anything else. Everything else is theirs: a class starts with no other categories at all, and
 * {@link #add} / {@link #remove} create and delete them. A category deleted while roads carry it
 * leaves those roads pointing at an id nothing defines; {@link #has} says so, and every reader draws
 * and edits such a road as "not classified".
 *
 * <h2>Why the definitions are here and not on the roads</h2>
 * A road stores only the category's opaque id; this store owns the name and colour. That is what
 * makes renaming a category repaint every road wearing it without touching the save file, and what
 * lets the same road look different in two players' games -- a category is presentation, like the
 * road class colours, not world data.
 *
 * <h2>File</h2>
 * {@code config/howtogo/road-categories.json}, read lazily on first use and written immediately on a
 * change -- a colour is changed by dragging a picker and the result has to survive the screen closing.
 * Only player-made categories are written; the built-in one is implicit, so a file is empty until the
 * player makes their first category.
 */
public final class RoadCategoryStore {

    private static final String FILE_NAME = "road-categories.json";
    private static final int FORMAT_VERSION = 2;

    /** What the built-in category is called and drawn as; neither is the player's to change. */
    private static final String NONE_NAME_KEY = "howtogo.road_category.none";
    private static final int NONE_COLOR = 0xFF7A7F87;
    /** The name a freshly added category starts with; the player renames it in the editor. */
    private static final String NEW_NAME_KEY = "howtogo.road_category.new";

    /**
     * The colours a new category is given, in turn, and the presets the picker offers.
     *
     * <p>Road-sign colours rather than a rainbow: the palette exists so that a player who adds three
     * categories gets three that are easy to tell apart on a map, not so that every possible colour
     * is one of them -- the picker takes any colour, these are only the starting points.
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

    /**
     * One player-made category: its id (which roads carry) and everything about how it looks.
     *
     * <p>A record rather than a {@link RoadCategory} because the category value deliberately knows
     * nothing about presentation -- see that class for why. The two are joined by the id.
     */
    private record Definition(String id, String name, int color) {
    }

    // ---------------------------------------------------------------- reading

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

    /**
     * Whether this category exists: the built-in one always does, a player-made one only while its
     * definition is in the store.
     *
     * <p>A road may carry an id this answers false for -- its category was deleted while it was
     * classified. Every reader treats that as the built-in category; this is the one question they
     * ask to find out.
     */
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

    /** The category itself when it exists, and the class's built-in one when it does not. */
    public static RoadCategory normalise(RoadCategory category) {
        if (category == null) {
            return noneOf(null);
        }
        return has(category) ? category : noneOf(category.roadClass());
    }

    /** Whether a category's name and colour are the player's to change; false only for the built-in one. */
    public static boolean isEditable(RoadCategory category) {
        return category != null && !category.isNone();
    }

    /**
     * The name a category is shown under.
     *
     * <p>The built-in one is translated every time, so it follows the language in force; a
     * player-made one is the text the player wrote, with the store's own default standing in for a
     * file somebody cleared by hand.
     */
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

    /**
     * The colour a category's sign is drawn in.
     *
     * <p>The built-in category's grey is fixed; a player-made category's colour is theirs, and a
     * category that no longer exists answers with the built-in grey so a deleted category's roads
     * still draw as something until they are cleaned up.
     */
    public static int color(RoadCategory category) {
        if (category == null || category.isNone()) {
            return NONE_COLOR;
        }
        ensureLoaded();
        Definition definition = definition(category);
        return definition == null ? NONE_COLOR : definition.color();
    }

    // ---------------------------------------------------------------- writing

    /**
     * Makes a new category for a class and returns it.
     *
     * <p>Starts with another palette colour and a placeholder name the player renames in the editor;
     * both are written at once, so a crash between adding and renaming loses nothing. The id is taken
     * from a per-class counter that is never reset, which is what keeps a deleted category's roads
     * from silently adopting the next one that is made.
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
     * Deletes a player-made category.
     *
     * <p>The built-in category is not deletable and answers false. Roads that carry the deleted one
     * are the caller's to put back to "not classified" -- the network is not this store's business --
     * and until they are, {@link #has} makes every reader treat them as unclassified anyway.
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

    /** Renames a player-made category; the built-in one cannot be renamed and ignores this. */
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

    /** Recolours a player-made category; the built-in one cannot be recoloured and ignores this. */
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

    // ------------------------------------------------------------------ file

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
                        // A row without an id, or with the reserved one, is not a category: dropped
                        // rather than reported, like every other hand-edited value in this mod.
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
            // A hand-edited file may have lost the counter: start past every id it holds so a new
            // category cannot adopt an id that is already in use.
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

    /** Writes immediately: a value the player just changed, in a file small enough to rewrite whole. */
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

    /** Gives {@code partial} the target's name, atomically where the filesystem allows it. */
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
        /** A colour in {@link ColorHex}'s spelled form. */
        String color;
    }
}
