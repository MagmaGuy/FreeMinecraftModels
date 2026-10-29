package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.freeminecraftmodels.config.PetsConfig;
import com.magmaguy.magmacore.util.ChatColorConverter;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A pet's passport: a written book opened from /fmm pets (right-click) or by sneaking and clicking the pet. Its pages
 * are identity, stats, life, family and babies. {@link #facts} reads them off the pet; {@link #pages} lays them out,
 * short lines that fit a book page (14 lines of about 19 characters), dark text on the page's paper.
 */
public final class PetPassport {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final int BAR = 20, BABIES_PER_PAGE = 12;

    /** A relative as a page shows it: its name, or its colour when it has none, and its colour. */
    record Kin(String name, String colour) {
    }

    /** A parent with its own parents under it. */
    record Parent(Kin kin, List<Kin> parents) {
    }

    /** Everything the pages show, read off the pet; NaN for a sting or poison base the pet file does not set. */
    record Facts(String name, String species, String colour, String rarity, int generation, String origin, String date,
                 String owner, double health, double maxHealth, PetStats stats, double cap, double stingBase,
                 double poisonSeconds, String effect, double size, boolean baby, long growsUpInMinutes, String status,
                 int babyCount, long breedsInMinutes, List<Parent> family, List<String> legacyParents, List<Kin> babies) {
    }

    private PetPassport() {
    }

    /** Opens the pet's passport for the player. */
    public static void open(PetManager manager, Player player, PetRecord record) {
        PetSpecies species = manager.species(record.species);
        if (species == null) return;
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle("Pet Passport");
        meta.setAuthor(player.getName());
        meta.setPages(pages(facts(manager, player, record, species)));
        book.setItemMeta(meta);
        player.openBook(book);
    }

    static Facts facts(PetManager manager, Player owner, PetRecord record, PetSpecies species) {
        long now = System.currentTimeMillis();
        PetStats stats = PetStats.orBase(record.stats);
        double share = record.baby ? PetStats.BABY_SHARE : 1D;
        double maxHealth = Math.max(1D, species.health() * stats.health * share);
        PetControl live = manager.liveBody(owner.getUniqueId(), record);
        LivingEntity body = live == null ? null : live.body();
        double health = body != null && body.isValid() ? body.getHealth() : record.health > 0D ? Math.min(record.health, maxHealth) : maxHealth;
        double durationTicks = species.stingSetting(record.model, "duration_ticks");
        String effect = species.stingEffect(record.model);
        long growsUp = !record.baby ? 0L : PetsConfig.babiesGrowUp
                ? Math.max(1L, (record.bornAt + species.babyGrowthMillis() - now + 59_999L) / 60_000L) : -1L;
        long breeds = Math.max(0L, (record.breedCooldownUntil - now + 59_999L) / 60_000L);
        List<Parent> family = new ArrayList<>();
        if (record.family != null)
            for (PetRecord.Relative parent : record.family) {
                List<Kin> grandparents = new ArrayList<>();
                if (parent.parents != null) for (PetRecord.Relative grandparent : parent.parents) grandparents.add(kin(manager, owner, species, grandparent));
                family.add(new Parent(kin(manager, owner, species, parent), grandparents));
            }
        List<Kin> babies = new ArrayList<>();
        if (record.babies != null) for (PetRecord.Relative baby : record.babies) babies.add(kin(manager, owner, species, baby));
        return new Facts(record.name == null || record.name.isBlank() ? null : plain(record.name), plain(species.name()),
                colour(species, record.model), species.rarityTier(record.model), record.generationOrFirst(),
                origin(record.origin), DATE.format(Instant.ofEpochMilli(record.bornAt).atZone(ZoneId.systemDefault())),
                owner.getName(), health, maxHealth, stats, species.statCap(),
                species.stingSetting(record.model, "damage") * share,
                Double.isNaN(durationTicks) ? Double.NaN : durationTicks / 20D * share, effect,
                PetStats.size(stats, species.statCap()), record.baby, growsUp, status(record), babies.size(),
                record.baby ? -1L : breeds, family, record.family == null && record.parents != null ? record.parents : List.of(),
                babies);
    }

    /** A relative's name: its current name tag name while its owner still has it, else the one it had, else its colour. */
    private static Kin kin(PetManager manager, Player owner, PetSpecies species, PetRecord.Relative relative) {
        String name = relative.name;
        if (relative.id != null) {
            try {
                PetRecord current = manager.collection(owner).find(UUID.fromString(relative.id));
                if (current != null) name = current.name;
            } catch (IllegalArgumentException notAnId) {
                // Keep the name it had.
            }
        }
        String colour = colour(species, relative.model);
        return new Kin(name == null || name.isBlank() ? colour : plain(name), colour);
    }

    /** A colour's name without its species, such as "Tidesurge" for "Tidesurge Driftjelly". */
    private static String colour(PetSpecies species, String model) {
        String full = plain(species.name(model)), speciesName = plain(species.name());
        if (full.endsWith(" " + speciesName)) return full.substring(0, full.length() - speciesName.length() - 1);
        return full;
    }

    private static String origin(String origin) {
        if (origin == null) return "Joined";
        return switch (origin) {
            case PetRecord.TAMED -> "Tamed";
            case PetRecord.BRED -> "Bred";
            case PetRecord.MUTATED -> "Bred, new colour";
            case PetRecord.GIVEN -> "Given";
            default -> "Joined";
        };
    }

    private static String status(PetRecord record) {
        if (!record.equipped) return "At home";
        if (record.stay != null)
            return "Staying at\n" + Math.round(record.stay.x) + " " + Math.round(record.stay.y) + " " + Math.round(record.stay.z);
        return "With you";
    }

    /** Text with its colour codes taken out, for the book's dark-on-paper colours. */
    private static String plain(String text) {
        String stripped = ChatColor.stripColor(ChatColorConverter.convert(text == null ? "" : text));
        return stripped == null ? "" : stripped;
    }

    /** The passport's pages, in order. */
    static List<String> pages(Facts facts) {
        List<String> pages = new ArrayList<>();
        pages.add(String.join("\n",
                "§l   Pet Passport",
                "§8-------------------",
                facts.name() == null ? "§0Name §7none yet\n§8use a name tag" : "§0Name §1" + facts.name(),
                "§0" + facts.species(),
                "§0Colour §1" + facts.colour(),
                "§0Rarity §2" + facts.rarity(),
                "§0Generation §1" + facts.generation(),
                "§0" + facts.origin() + " §8" + facts.date(),
                "§0Owner §1" + facts.owner()));
        PetStats stats = facts.stats();
        pages.add(String.join("\n",
                "§lStats",
                "§0Health §1" + Math.round(facts.health()) + "/" + Math.round(facts.maxHealth()),
                bar(stats.health, facts.cap()),
                "§0Speed §1" + PetStats.percent(stats.speed),
                bar(stats.speed, facts.cap()),
                "§0Sting §1" + (Double.isNaN(facts.stingBase()) ? PetStats.percent(stats.sting)
                        : String.format(Locale.ROOT, "%.1f", facts.stingBase() * stats.sting)),
                bar(stats.sting, facts.cap()),
                "§0" + effectLabel(facts.effect()) + " §1" + (Double.isNaN(facts.poisonSeconds()) ? PetStats.percent(stats.poison)
                        : String.format(Locale.ROOT, "%.1fs", facts.poisonSeconds() * stats.poison)),
                bar(stats.poison, facts.cap()),
                "§0Size §1" + PetStats.percent(facts.size()),
                "§8Bars run from " + PetStats.percent(PetStats.FLOOR),
                "§8to " + PetStats.percent(facts.cap()) + " of normal"));
        pages.add(String.join("\n",
                "§lLife",
                "§0Age §1" + (facts.baby() ? "baby" : "adult"),
                facts.baby() ? (facts.growsUpInMinutes() < 0 ? "§8stays a baby" : "§8grows up in " + minutes(facts.growsUpInMinutes())) : "",
                "§0Status",
                "§1" + facts.status(),
                "§0Babies §1" + facts.babyCount(),
                "§0Breeding",
                facts.breedsInMinutes() < 0 ? "§8not while a baby" : facts.breedsInMinutes() == 0 ? "§2ready"
                        : "§8again in " + minutes(facts.breedsInMinutes())));
        pages.add(family(facts));
        List<Kin> babies = new ArrayList<>(facts.babies());
        java.util.Collections.reverse(babies);
        for (int start = 0; start < babies.size(); start += BABIES_PER_PAGE) {
            List<String> lines = new ArrayList<>();
            lines.add("§lBabies §8(" + babies.size() + ")");
            for (Kin baby : babies.subList(start, Math.min(babies.size(), start + BABIES_PER_PAGE)))
                lines.add("§1" + baby.name() + (baby.name().equals(baby.colour()) ? "" : " §8" + baby.colour()));
            pages.add(String.join("\n", lines));
        }
        return pages;
    }

    private static String family(Facts facts) {
        List<String> lines = new ArrayList<>();
        lines.add("§lFamily");
        if (!facts.family().isEmpty()) {
            lines.add("§0Parents");
            for (Parent parent : facts.family()) {
                Kin kin = parent.kin();
                lines.add("§1" + kin.name() + (kin.name().equals(kin.colour()) ? "" : " §8" + kin.colour()));
                if (!parent.parents().isEmpty())
                    lines.add("§8 from " + String.join(" & ", parent.parents().stream().map(Kin::name).toList()));
            }
        } else if (!facts.legacyParents().isEmpty()) {
            lines.add("§0Parents");
            for (String name : facts.legacyParents()) lines.add("§1" + plain(name));
            lines.add("§8Bred before family");
            lines.add("§8trees were kept.");
        } else {
            lines.add("§8" + switch (facts.origin()) {
                case "Tamed" -> "Tamed in the wild:";
                case "Given" -> "Given:";
                default -> "Joined:";
            });
            lines.add("§8no family on record.");
        }
        return String.join("\n", lines);
    }

    /** A stat's bar: green up to the stat, grey to the cap, from the floor at its left. */
    static String bar(double factor, double cap) {
        double span = Math.max(1e-9, cap - PetStats.FLOOR);
        int filled = (int) Math.round(BAR * Math.max(0D, Math.min(1D, (factor - PetStats.FLOOR) / span)));
        return "§2" + "|".repeat(filled) + "§7" + "|".repeat(BAR - filled);
    }

    private static String effectLabel(String effect) {
        if (effect == null || effect.isBlank() || effect.equalsIgnoreCase("NONE")) return "Poison";
        String name = effect.contains(":") ? effect.substring(effect.indexOf(':') + 1) : effect;
        name = name.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static String minutes(long minutes) {
        return minutes < 60 ? minutes + " min" : (minutes / 60) + " h " + (minutes % 60) + " min";
    }
}
