package com.scpl.service;

import com.scpl.dto.ImportKind;
import com.scpl.model.Award;
import com.scpl.model.AwardCategory;
import com.scpl.model.BattingStyle;
import com.scpl.model.BillStatus;
import com.scpl.model.BowlingStyle;
import com.scpl.model.Match;
import com.scpl.model.MatchStage;
import com.scpl.model.MatchStatus;
import com.scpl.model.Player;
import com.scpl.model.PlayerRole;
import com.scpl.model.Season;
import com.scpl.model.SeasonStatus;
import com.scpl.model.Society;
import com.scpl.model.Team;
import com.scpl.model.VendorBill;
import com.scpl.model.VendorCategory;
import com.scpl.model.VendorContact;
import com.scpl.repo.AwardRepository;
import com.scpl.repo.MatchRepository;
import com.scpl.repo.PlayerRepository;
import com.scpl.repo.SeasonRepository;
import com.scpl.repo.SocietyRepository;
import com.scpl.repo.TeamRepository;
import com.scpl.repo.VendorBillRepository;
import com.scpl.repo.VendorContactRepository;
import com.scpl.util.ImportValues;
import com.scpl.util.PlanCatalog;
import com.scpl.util.Text;
import com.scpl.util.Urls;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Decides every import row. The same process() runs in review mode (commit=false, nothing written)
 * and when an approved batch is applied (commit=true), so the review file can never disagree with the
 * import. One instance serves one pass over one society and must be built inside a transaction.
 *
 * <p>Rules are checked in a fixed order: format, references, duplicates, plan quota. A row is only
 * registered as "seen" once it passes, so a bad first row never blocks a good later duplicate.
 * Duplicates inside the batch are reported as duplicate_in_file; clashes with stored data use
 * specific codes (email_exists, team_exists, ...). Rows must be fed in ImportKind order so teams,
 * players and vendors exist before the matches, awards and bills that refer to them.
 */
final class ImportRowHandler {

    record Deps(SocietyRepository societies, SeasonRepository seasons, TeamRepository teams,
                PlayerRepository players, VendorContactRepository vendors, VendorBillRepository bills,
                MatchRepository matches, AwardRepository awards, double defaultBasePrice) {
    }

    /** A row-level rejection. Carries only a code, so building one is cheap. */
    static final class RowProblem extends RuntimeException {
        final String code;

        RowProblem(String code) {
            super(code, null, false, false);
            this.code = code;
        }
    }

    private final Deps d;
    private final Society society;
    private final boolean commit;
    private final PlanCatalog.Limits limits;

    private final Map<String, Season> seasonsByName = new HashMap<>();
    private final List<Season> openSeasons = new ArrayList<>();
    private final Map<String, Team> teamsByKey = new HashMap<>();
    private final Set<String> proposedTeams = new HashSet<>();
    private final Map<Long, Integer> teamCount = new HashMap<>();
    private final Map<String, List<Player>> playersByName = new HashMap<>();
    private final Map<String, Player> playersByEmail = new HashMap<>();
    private final Set<String> proposedPlayerNames = new HashSet<>();
    private final Set<String> proposedPlayerEmails = new HashSet<>();
    private long playerRoom;
    private final Map<String, VendorContact> vendorsByName = new HashMap<>();
    private final Set<String> proposedVendors = new HashSet<>();
    private final Set<String> existingMatches = new HashSet<>();
    private final Set<String> proposedMatches = new HashSet<>();
    private final Set<String> existingAwards = new HashSet<>();
    private final Set<String> proposedAwards = new HashSet<>();
    private final Set<String> existingBills = new HashSet<>();
    private final Set<String> proposedBills = new HashSet<>();

    ImportRowHandler(Deps deps, Long societyId, PlanCatalog.Limits limits, boolean commit) {
        this.d = deps;
        this.limits = limits;
        this.commit = commit;
        this.society = deps.societies().getReferenceById(societyId);

        for (Season s : deps.seasons().findBySociety_IdOrderByCreatedAtDesc(societyId)) {
            seasonsByName.putIfAbsent(lower(s.getName()), s);
            if (s.getStatus() != SeasonStatus.COMPLETED) {
                openSeasons.add(s);
            }
        }
        for (Team t : deps.teams().findBySociety_IdOrderByNameAsc(societyId)) {
            teamsByKey.put(teamKey(t.getSeasonId(), t.getName()), t);
            teamCount.merge(t.getSeasonId(), 1, Integer::sum);
        }
        long active = 0;
        for (Player p : deps.players().findBySociety_IdOrderByNameAsc(societyId)) {
            playersByName.computeIfAbsent(lower(p.getName()), k -> new ArrayList<>()).add(p);
            if (p.getEmail() != null) {
                playersByEmail.put(lower(p.getEmail()), p);
            }
            if (Boolean.TRUE.equals(p.getActive())) {
                active++;
            }
        }
        playerRoom = limits.maxPlayers() == PlanCatalog.UNLIMITED ? Long.MAX_VALUE
                : Math.max(0, limits.maxPlayers() - active);

        if (limits.vendorModule()) {
            for (VendorContact v : deps.vendors().findBySociety_IdOrderByNameAsc(societyId)) {
                vendorsByName.put(lower(v.getName()), v);
            }
            for (VendorBill b : deps.bills().findBySociety_IdOrderByBillDateDesc(societyId)) {
                if (b.getBillDate() != null) {
                    existingBills.add(billKey(lower(b.getVendor().getName()), b.getTitle(), b.getAmount(), b.getBillDate()));
                }
            }
        }
        for (Match m : deps.matches().findBySociety_IdOrderByMatchDateAsc(societyId)) {
            existingMatches.add(matchKey(m.getSeason().getId(), lower(m.getTeamA().getName()),
                    lower(m.getTeamB().getName()), m.getMatchDate()));
        }
        for (Award a : deps.awards().findBySociety_IdOrderByCreatedAtDesc(societyId)) {
            existingAwards.add(awardKey(a.getTitle(), a.getSeason() == null ? null : a.getSeason().getId(),
                    a.getPlayer() == null ? null : a.getPlayer().getName(),
                    a.getTeam() == null ? null : a.getTeam().getName()));
        }
    }

    /** Returns null when the row is accepted (and, in commit mode, stored), otherwise the conflict code. */
    String process(ImportKind kind, Map<String, String> f) {
        try {
            String problem = ImportValues.formatProblem(kind, f);
            if (problem != null) {
                throw new RowProblem(problem);
            }
            switch (kind) {
                case PLAYER -> player(f);
                case TEAM -> team(f);
                case VENDOR -> vendor(f);
                case MATCH -> match(f);
                case AWARD -> award(f);
                case BILL -> bill(f);
            }
            return null;
        } catch (RowProblem p) {
            return p.code;
        } catch (IllegalArgumentException e) {
            // A parser rejected a value that formatProblem should have caught: report, never abort.
            return "invalid_value";
        }
    }

    // ------------------------------------------------------------------ players and teams

    private void player(Map<String, String> f) {
        String name = f.get("name").trim();
        String email = ImportValues.blank(f.get("email")) ? null : lower(f.get("email"));
        if (email != null) {
            if (proposedPlayerEmails.contains(email)) {
                throw new RowProblem("duplicate_in_file");
            }
            if (playersByEmail.containsKey(email)) {
                throw new RowProblem("email_exists");
            }
        } else {
            // Without an email the name is the only identity, so a name clash is flagged.
            String nk = lower(name);
            if (proposedPlayerNames.contains(nk)) {
                throw new RowProblem("duplicate_in_file");
            }
            if (playersByName.containsKey(nk)) {
                throw new RowProblem("duplicate_name");
            }
        }
        if (playerRoom <= 0) {
            throw new RowProblem("quota_exceeded");
        }
        playerRoom--;
        proposedPlayerNames.add(lower(name));
        if (email != null) {
            proposedPlayerEmails.add(email);
        }
        if (!commit) {
            return;
        }
        Player p = new Player();
        p.setSociety(society);
        p.setName(name);
        p.setEmail(Text.clean(f.get("email")));
        p.setPhone(Text.clean(f.get("phone")));
        if (!ImportValues.blank(f.get("dateOfBirth"))) {
            p.setDateOfBirth(ImportValues.parseDate(f.get("dateOfBirth")));
        }
        p.setFlatNumber(Text.clean(f.get("flatNumber")));
        p.setAddress(Text.clean(f.get("address")));
        p.setRole(enumOrNull(PlayerRole.class, f.get("role"), PlayerRole::getLabel));
        p.setBattingStyle(enumOrNull(BattingStyle.class, f.get("battingStyle"), BattingStyle::getLabel));
        p.setBowlingStyle(enumOrNull(BowlingStyle.class, f.get("bowlingStyle"), BowlingStyle::getLabel));
        if (!ImportValues.blank(f.get("jerseyNumber"))) {
            p.setJerseyNumber(ImportValues.parseInt(f.get("jerseyNumber")));
        }
        p.setBasePrice(ImportValues.blank(f.get("basePrice")) ? d.defaultBasePrice()
                : ImportValues.parseMoney(f.get("basePrice")));
        Player saved = d.players().save(p);
        playersByName.computeIfAbsent(lower(name), k -> new ArrayList<>()).add(saved);
        if (email != null) {
            playersByEmail.put(email, saved);
        }
    }

    private void team(Map<String, String> f) {
        Season season = resolveSeason(f.get("season"));
        String key = teamKey(season.getId(), f.get("name"));
        if (proposedTeams.contains(key)) {
            throw new RowProblem("duplicate_in_file");
        }
        if (teamsByKey.containsKey(key)) {
            throw new RowProblem("team_exists");
        }
        int count = teamCount.getOrDefault(season.getId(), 0);
        if (count >= limits.maxTeams()) {
            throw new RowProblem("quota_exceeded");
        }
        teamCount.put(season.getId(), count + 1);
        proposedTeams.add(key);
        if (!commit) {
            return;
        }
        Team t = new Team();
        t.setSociety(society);
        t.setSeason(season);
        t.setName(f.get("name").trim());
        t.setShortName(Text.clean(f.get("shortName")));
        t.setColor(Text.clean(f.get("color")));
        t.setPurseRemaining(season.getTeamPurse());
        teamsByKey.put(key, d.teams().save(t));
    }

    // ------------------------------------------------------------------ vendors and bills

    private void vendor(Map<String, String> f) {
        requireVendorPlan();
        String key = lower(f.get("name"));
        if (proposedVendors.contains(key)) {
            throw new RowProblem("duplicate_in_file");
        }
        if (vendorsByName.containsKey(key)) {
            throw new RowProblem("vendor_exists");
        }
        proposedVendors.add(key);
        if (!commit) {
            return;
        }
        VendorContact v = new VendorContact();
        v.setSociety(society);
        v.setName(f.get("name").trim());
        VendorCategory category = enumOrNull(VendorCategory.class, f.get("category"), VendorCategory::getLabel);
        v.setCategory(category == null ? VendorCategory.OTHER : category);
        v.setContactPerson(Text.clean(f.get("contactPerson")));
        v.setPhone(Text.clean(f.get("phone")));
        v.setWhatsappNumber(Text.clean(f.get("whatsapp")));
        v.setEmail(Text.clean(f.get("email")));
        v.setAddress(Text.clean(f.get("address")));
        v.setCity(Text.clean(f.get("city")));
        v.setWebsite(Urls.requireWebUrl(f.get("website"), "Website"));
        if (!ImportValues.blank(f.get("rating"))) {
            v.setRating(ImportValues.parseInt(f.get("rating")));
        }
        v.setNotes(Text.clean(f.get("notes")));
        v.setActive(true);
        vendorsByName.put(key, d.vendors().save(v));
    }

    private void bill(Map<String, String> f) {
        requireVendorPlan();
        String vendorKey = lower(f.get("vendor"));
        VendorContact vendor = vendorsByName.get(vendorKey);
        if (vendor == null && !proposedVendors.contains(vendorKey)) {
            throw new RowProblem("unknown_vendor");
        }
        Season season = ImportValues.blank(f.get("season")) ? null : resolveSeason(f.get("season"));
        double amount = ImportValues.parseMoney(f.get("amount"));
        LocalDate date = ImportValues.blank(f.get("billDate")) ? LocalDate.now() : ImportValues.parseDate(f.get("billDate"));
        String key = billKey(vendorKey, f.get("title"), amount, date);
        if (proposedBills.contains(key)) {
            throw new RowProblem("duplicate_in_file");
        }
        if (existingBills.contains(key)) {
            throw new RowProblem("bill_exists");
        }
        proposedBills.add(key);
        if (!commit) {
            return;
        }
        VendorBill b = new VendorBill();
        b.setSociety(society);
        b.setVendor(vendor);
        b.setSeason(season);
        b.setTitle(f.get("title").trim());
        b.setAmount(amount);
        b.setBillDate(date);
        b.setDescription(Text.clean(f.get("description")));
        b.setStatus(BillStatus.PENDING); // imports never mark a bill paid: that needs an admin to record who paid
        d.bills().save(b);
    }

    // ------------------------------------------------------------------ matches and awards

    private void match(Map<String, String> f) {
        Season season = resolveSeason(f.get("season"));
        String a = lower(f.get("teamA"));
        String b = lower(f.get("teamB"));
        if (a.equals(b)) {
            throw new RowProblem("same_team");
        }
        String ka = teamKey(season.getId(), f.get("teamA"));
        String kb = teamKey(season.getId(), f.get("teamB"));
        if (!teamKnown(ka) || !teamKnown(kb)) {
            throw new RowProblem("unknown_team");
        }
        LocalDateTime when = ImportValues.blank(f.get("date")) ? null : ImportValues.parseDateTime(f.get("date"));
        String key = matchKey(season.getId(), a, b, when);
        if (proposedMatches.contains(key)) {
            throw new RowProblem("duplicate_in_file");
        }
        if (existingMatches.contains(key)) {
            throw new RowProblem("match_exists");
        }
        proposedMatches.add(key);
        if (!commit) {
            return;
        }
        Match m = new Match();
        m.setSociety(society);
        m.setSeason(season);
        m.setTeamA(teamsByKey.get(ka));
        m.setTeamB(teamsByKey.get(kb));
        m.setMatchDate(when);
        m.setVenue(Text.clean(f.get("venue")));
        MatchStage stage = enumOrNull(MatchStage.class, f.get("stage"), MatchStage::getLabel);
        m.setStage(stage == null ? MatchStage.LEAGUE : stage);
        m.setStatus(MatchStatus.SCHEDULED); // results are recorded through the normal match flow
        d.matches().save(m);
    }

    private void award(Map<String, String> f) {
        Season season = ImportValues.blank(f.get("season")) ? null : resolveSeason(f.get("season"));
        String pn = ImportValues.blank(f.get("player")) ? null : f.get("player").trim();
        String tn = ImportValues.blank(f.get("team")) ? null : f.get("team").trim();

        Player player = null;
        String playerName = null;
        if (pn != null) {
            List<Player> found = playersByName.get(lower(pn));
            if (found != null) {
                if (found.size() > 1) {
                    throw new RowProblem("ambiguous_player");
                }
                player = found.get(0);
                playerName = player.getName();
            } else if (proposedPlayerNames.contains(lower(pn))) {
                playerName = pn;
            }
        }
        Team team = null;
        String teamName = null;
        if (pn != null && playerName == null && tn != null) {
            throw new RowProblem("unknown_player");
        }
        if (tn != null) {
            team = findTeam(season, tn);
            if (team == null && !teamProposedFor(season, tn)) {
                throw new RowProblem("unknown_team");
            }
            teamName = team != null ? team.getName() : tn;
        } else if (pn != null && playerName == null) {
            // A lone "winner" column may name a team rather than a player.
            team = findTeam(season, pn);
            if (team != null) {
                teamName = team.getName();
            } else if (teamProposedFor(season, pn)) {
                teamName = pn;
            } else {
                throw new RowProblem("unknown_player");
            }
        }
        String key = awardKey(f.get("title"), season == null ? null : season.getId(), playerName, teamName);
        if (proposedAwards.contains(key)) {
            throw new RowProblem("duplicate_in_file");
        }
        if (existingAwards.contains(key)) {
            throw new RowProblem("award_exists");
        }
        proposedAwards.add(key);
        if (!commit) {
            return;
        }
        Award a = new Award();
        a.setSociety(society);
        a.setTitle(f.get("title").trim());
        a.setCategory(enumOrNull(AwardCategory.class, f.get("category"), AwardCategory::getLabel));
        a.setDescription(Text.clean(f.get("description")));
        a.setSeason(season);
        a.setPlayer(player);
        a.setTeam(team);
        d.awards().save(a);
    }

    // ------------------------------------------------------------------ lookups

    private void requireVendorPlan() {
        if (!limits.vendorModule()) {
            throw new RowProblem("plan_excludes_vendors");
        }
    }

    /** A blank season means "the current season", but only when there is exactly one to mean. */
    private Season resolveSeason(String name) {
        if (ImportValues.blank(name)) {
            if (openSeasons.size() == 1) {
                return openSeasons.get(0);
            }
            throw new RowProblem(openSeasons.isEmpty() ? "unknown_season" : "ambiguous_season");
        }
        Season s = seasonsByName.get(lower(name));
        if (s == null) {
            throw new RowProblem("unknown_season");
        }
        return s;
    }

    private boolean teamKnown(String key) {
        return teamsByKey.containsKey(key) || proposedTeams.contains(key);
    }

    private Team findTeam(Season season, String name) {
        if (season != null) {
            return teamsByKey.get(teamKey(season.getId(), name));
        }
        String n = lower(name);
        Team hit = null;
        for (Team t : teamsByKey.values()) {
            if (lower(t.getName()).equals(n)) {
                if (hit != null && !hit.getId().equals(t.getId())) {
                    throw new RowProblem("ambiguous_season");
                }
                hit = t;
            }
        }
        return hit;
    }

    private boolean teamProposedFor(Season season, String name) {
        if (season != null) {
            return proposedTeams.contains(teamKey(season.getId(), name));
        }
        String suffix = "|" + lower(name);
        return proposedTeams.stream().anyMatch(k -> k.endsWith(suffix));
    }

    // ------------------------------------------------------------------ keys and helpers

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String teamKey(Long seasonId, String name) {
        return seasonId + "|" + lower(name);
    }

    private static String matchKey(Long seasonId, String a, String b, LocalDateTime when) {
        String pair = a.compareTo(b) <= 0 ? a + "~" + b : b + "~" + a;
        return seasonId + "|" + pair + "|" + (when == null ? "" : when);
    }

    private static String awardKey(String title, Long seasonId, String playerName, String teamName) {
        return lower(title) + "|" + (seasonId == null ? 0 : seasonId) + "|"
                + (playerName == null ? "" : "p:" + lower(playerName)) + "|"
                + (teamName == null ? "" : "t:" + lower(teamName));
    }

    private static String billKey(String vendorLower, String title, double amount, LocalDate date) {
        return vendorLower + "|" + lower(title) + "|" + amount + "|" + date;
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String raw, Function<E, String> label) {
        return ImportValues.blank(raw) ? null : ImportValues.parseEnum(type, raw, label);
    }
}