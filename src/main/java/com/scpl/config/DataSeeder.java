package com.scpl.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scpl.dto.ImportKind;
import com.scpl.dto.ImportRows.ReviewRow;
import com.scpl.dto.ImportRows.SegregationReport;
import com.scpl.dto.ImportRows.TableSummary;
import com.scpl.dto.NewAdminRequest;
import com.scpl.dto.SocietyRequest;
import com.scpl.model.AdminContact;
import com.scpl.model.AuctionBid;
import com.scpl.model.AuctionLot;
import com.scpl.model.AuctionMode;
import com.scpl.model.Award;
import com.scpl.model.AwardCategory;
import com.scpl.model.BattingStyle;
import com.scpl.model.BatchStatus;
import com.scpl.model.BillStatus;
import com.scpl.model.BowlingStyle;
import com.scpl.model.EventScope;
import com.scpl.model.EventType;
import com.scpl.model.GalleryImage;
import com.scpl.model.Invoice;
import com.scpl.model.InvoiceStatus;
import com.scpl.model.LotStatus;
import com.scpl.model.Match;
import com.scpl.model.MatchStage;
import com.scpl.model.MatchStatus;
import com.scpl.model.PlatformEvent;
import com.scpl.model.PlatformRole;
import com.scpl.model.Player;
import com.scpl.model.PlayerRole;
import com.scpl.model.Registration;
import com.scpl.model.RegistrationStatus;
import com.scpl.model.Season;
import com.scpl.model.SeasonStatus;
import com.scpl.model.Society;
import com.scpl.model.Sponsor;
import com.scpl.model.SponsorTier;
import com.scpl.model.Subscription;
import com.scpl.model.SubscriptionPlan;
import com.scpl.model.SubscriptionStatus;
import com.scpl.model.Team;
import com.scpl.model.UploadBatch;
import com.scpl.model.User;
import com.scpl.model.VendorBill;
import com.scpl.model.VendorCategory;
import com.scpl.model.VendorContact;
import com.scpl.repo.AdminContactRepository;
import com.scpl.repo.AuctionBidRepository;
import com.scpl.repo.AuctionLotRepository;
import com.scpl.repo.AwardRepository;
import com.scpl.repo.GalleryImageRepository;
import com.scpl.repo.InvoiceRepository;
import com.scpl.repo.MatchRepository;
import com.scpl.repo.PlatformEventRepository;
import com.scpl.repo.PlayerRepository;
import com.scpl.repo.RegistrationRepository;
import com.scpl.repo.SeasonRepository;
import com.scpl.repo.SocietyRepository;
import com.scpl.repo.SponsorRepository;
import com.scpl.repo.SubscriptionRepository;
import com.scpl.repo.TeamRepository;
import com.scpl.repo.UploadBatchRepository;
import com.scpl.repo.UserRepository;
import com.scpl.repo.VendorBillRepository;
import com.scpl.repo.VendorContactRepository;
import com.scpl.service.NotificationService;
import com.scpl.service.SocietyService;
import com.scpl.service.SubscriptionService;
import com.scpl.util.PlanCatalog;
import com.scpl.util.ReviewCsv;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Demo data (§18). Runs once, only when scpl.seed.enabled=true (the dev profile), never on the prod profile, and only
 * when no society exists yet. Everything is written in one transaction (files last), so a failed run leaves nothing
 * behind and the next start tries again.
 *
 * <p>Societies, admins, activation and the trial go through the real services, so those paths are exercised on every
 * start. League rows are written through repositories on purpose: the quota checks in the services would otherwise
 * refuse the 4 teams the spec asks for on Sunrise Heights' 2-team trial.
 */
@Component
@ConditionalOnProperty(name = "scpl.seed.enabled", havingValue = "true")
public class DataSeeder implements ApplicationRunner {

    static final String PASSWORD = "Passw0rd!";
    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private static final int PLAYERS_PER_SOCIETY = 12;
    private static final double TEAM_PURSE = 50_000.0;
    private static final double BASE_PRICE = 1_000.0;
    /** Which team each of the 12 players was sold to (-1 = unsold, lot still PENDING). */
    private static final int[] TEAM_OF = {0, 1, 2, 3, 0, 1, 2, 3, 0, 1, -1, -1};
    private static final int[][] PAIRS = {{0, 1}, {2, 3}, {0, 2}, {1, 3}, {0, 3}, {1, 2}};
    /** Completed fixtures: runsA, wicketsA, runsB, wicketsB. */
    private static final int[][] SCORES = {{92, 4, 85, 7}, {78, 10, 101, 3}, {110, 5, 110, 6}, {64, 9, 70, 2}};
    private static final double[][] OVERS = {{10.0, 10.0}, {9.3, 10.0}, {10.0, 10.0}, {10.0, 8.4}};
    private static final int[] DAYS_AGO = {14, 12, 7, 5};
    private static final int[] DAYS_AHEAD = {3, 6};

    private record Spec(String name, String slug, String city, String address, String description, String prefix,
                        String domain, boolean paid, String seasonName, int year, String[][] admins,
                        String[] players, String[][] teams, String[][] sponsors, String[][] vendors,
                        String[][] bills) {
    }

    private final SocietyRepository societies;
    private final UserRepository users;
    private final SeasonRepository seasons;
    private final TeamRepository teams;
    private final PlayerRepository players;
    private final MatchRepository matches;
    private final RegistrationRepository registrations;
    private final AuctionLotRepository lots;
    private final AuctionBidRepository bids;
    private final SponsorRepository sponsors;
    private final AwardRepository awards;
    private final GalleryImageRepository gallery;
    private final VendorContactRepository vendors;
    private final VendorBillRepository bills;
    private final AdminContactRepository contacts;
    private final SubscriptionRepository subscriptions;
    private final InvoiceRepository invoices;
    private final PlatformEventRepository events;
    private final UploadBatchRepository batches;
    private final SocietyService societyService;
    private final SubscriptionService subscriptionService;
    private final NotificationService notifications;
    private final PasswordEncoder encoder;
    private final ObjectMapper mapper;
    private final FeatureFlags flags;
    private final Environment env;
    private final TransactionTemplate tx;

    @Value("${scpl.uploads.dir:./data/uploads}")
    private String uploadsDir;
    @Value("${scpl.public-base-url:http://localhost:8080}")
    private String baseUrl;

    private String passwordHash;
    private int phoneCounter;

    public DataSeeder(SocietyRepository societies, UserRepository users, SeasonRepository seasons,
                      TeamRepository teams, PlayerRepository players, MatchRepository matches,
                      RegistrationRepository registrations, AuctionLotRepository lots, AuctionBidRepository bids,
                      SponsorRepository sponsors, AwardRepository awards, GalleryImageRepository gallery,
                      VendorContactRepository vendors, VendorBillRepository bills, AdminContactRepository contacts,
                      SubscriptionRepository subscriptions, InvoiceRepository invoices,
                      PlatformEventRepository events, UploadBatchRepository batches, SocietyService societyService,
                      SubscriptionService subscriptionService, NotificationService notifications,
                      PasswordEncoder encoder, ObjectMapper mapper, FeatureFlags flags, Environment env,
                      PlatformTransactionManager txManager) {
        this.societies = societies;
        this.users = users;
        this.seasons = seasons;
        this.teams = teams;
        this.players = players;
        this.matches = matches;
        this.registrations = registrations;
        this.lots = lots;
        this.bids = bids;
        this.sponsors = sponsors;
        this.awards = awards;
        this.gallery = gallery;
        this.vendors = vendors;
        this.bills = bills;
        this.contacts = contacts;
        this.subscriptions = subscriptions;
        this.invoices = invoices;
        this.events = events;
        this.batches = batches;
        this.societyService = societyService;
        this.subscriptionService = subscriptionService;
        this.notifications = notifications;
        this.encoder = encoder;
        this.mapper = mapper;
        this.flags = flags;
        this.env = env;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (env.acceptsProfiles(Profiles.of("prod"))) {
            log.warn("scpl.seed.enabled is true but the prod profile is active: demo data is NOT loaded (its passwords are public)");
            return;
        }
        if (societies.count() > 0) {
            log.info("Demo data not loaded: societies already exist");
            return;
        }
        tx.executeWithoutResult(s -> seed());
        log.info("✅ SCPL demo data loaded. Open {}", baseUrl);
        if (flags.isDevLogCredentials()) {
            logCredentials();
        }
    }

    // ------------------------------------------------------------------ orchestration

    private void seed() {
        passwordHash = encoder.encode(PASSWORD); // one hash, reused for the directly created users
        User platform = users.findByUsernameIgnoreCase("platformadmin").orElseGet(() -> users.save(
                newUser("platformadmin", "platform@scpl.example", "Platform Admin", PlatformRole.PLATFORM_ADMIN, null, null)));
        Society gv = seedSociety(greenValley(), platform, true);
        Society sh = seedSociety(sunriseHeights(), platform, false);
        seedEvents(gv, sh);
    }

    private Society seedSociety(Spec sp, User platform, boolean winterCup) {
        // ---- society, founding admins, activation (real service paths)
        List<NewAdminRequest> founders = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String full = sp.admins()[i][0];
            founders.add(new NewAdminRequest(sp.prefix() + ".admin" + (i + 1), emailOf(full, sp.domain()), full, phone(), PASSWORD));
        }
        Society society = societyService.createWithAdmins(new SocietyRequest(sp.name(), sp.slug(), sp.description(),
                sp.city(), sp.address(), "committee@" + sp.domain(), phone()), founders, platform);
        societyService.activate(society.getId(), platform);

        User admin1 = users.findByUsernameIgnoreCase(sp.prefix() + ".admin1").orElseThrow();
        User admin3 = users.findByUsernameIgnoreCase(sp.prefix() + ".admin3").orElseThrow();
        Map<String, String> titles = new HashMap<>();
        for (int i = 0; i < 3; i++) {
            titles.put(sp.prefix() + ".admin" + (i + 1), sp.admins()[i][1]);
        }
        for (AdminContact c : contacts.findBySociety_IdOrderByNameAsc(society.getId())) {
            if (c.getUser() != null) {
                c.setRoleTitle(titles.get(c.getUser().getUsername()));
            }
        }
        AdminContact groundStaff = new AdminContact();
        groundStaff.setSociety(society);
        groundStaff.setName("Ground Manager");
        groundStaff.setRoleTitle("Ground staff (no login)");
        groundStaff.setPhone(phone());
        groundStaff.setWhatsappNumber(groundStaff.getPhone());
        contacts.save(groundStaff);

        seedSubscription(society, sp.paid());

        // ---- league
        Season season = season(society, sp.seasonName(), sp.year(), SeasonStatus.ONGOING, false);
        List<Player> roster = seedPlayers(society, sp, admin3);
        List<Team> teamList = seedTeams(society, season, sp, roster);
        seedAuction(society, season, roster, teamList);
        seedMatches(society, season, teamList);
        seedSponsorsAwardsGallery(society, season, sp, roster, teamList);
        seedVendorsAndBills(society, season, sp, admin1);

        // ---- logins that play: a captain's player login, and the admin3-is-also-a-player link (dual role, §6)
        users.save(newUser(sp.prefix() + ".player1", roster.get(0).getEmail(), roster.get(0).getName(),
                PlatformRole.SOCIETY_PLAYER, society, roster.get(0)));
        admin3.setPlayer(roster.get(PLAYERS_PER_SOCIETY - 1));
        users.save(admin3);

        if (winterCup) {
            seedWinterCupRegistrations(society);
        }
        seedImportBatches(society, admin1, sp, roster, winterCup);
        return society;
    }

    // ------------------------------------------------------------------ subscription

    private void seedSubscription(Society society, boolean paid) {
        // Sunrise Heights stays on its trial, having picked Standard as the plan to convert to.
        Subscription sub = subscriptionService.startTrial(society, paid ? null : SubscriptionPlan.STANDARD);
        if (!paid) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        double cost = PlanCatalog.of(SubscriptionPlan.STANDARD).monthlyCost();
        sub.setPlan(SubscriptionPlan.STANDARD);
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setStartedAt(now.minusDays(40));
        sub.setMonthlyCost(cost);
        sub.setLastPaymentAt(now.minusDays(10));
        sub.setNextBillingAt(now.plusDays(20));
        sub.setExpiresAt(now.plusDays(20));
        sub.setNextPlan(null);
        subscriptions.save(sub);

        Invoice invoice = new Invoice();
        invoice.setSociety(society);
        invoice.setSubscription(sub);
        invoice.setInvoiceNumber(String.format(Locale.ROOT, "INV-%d-%s-001", society.getId(),
                DateTimeFormatter.BASIC_ISO_DATE.format(now.minusDays(10))));
        invoice.setAmount(cost);
        invoice.setCurrency("INR");
        invoice.setStatus(InvoiceStatus.PAID);
        invoice.setIssuedAt(now.minusDays(10));
        invoice.setPaidAt(now.minusDays(10));
        invoices.save(invoice);
    }

    // ------------------------------------------------------------------ league

    private Season season(Society society, String name, int year, SeasonStatus status, boolean registrationOpen) {
        LocalDate today = LocalDate.now();
        Season s = new Season();
        s.setSociety(society);
        s.setName(name);
        s.setYear(year);
        s.setStatus(status);
        s.setStartDate(registrationOpen ? today.plusDays(60) : today.minusDays(20));
        s.setEndDate(registrationOpen ? today.plusDays(90) : today.plusDays(30));
        s.setRegistrationOpen(registrationOpen);
        s.setOversPerMatch(10);
        s.setTeamPurse(TEAM_PURSE);
        s.setPlannedBudget(registrationOpen ? 60_000.0 : 80_000.0);
        s.setDescription(registrationOpen ? "Registrations are open for the winter tournament."
                : "Ten-over league played on weekday evenings.");
        return seasons.save(s);
    }

    private List<Player> seedPlayers(Society society, Spec sp, User admin3) {
        List<Player> out = new ArrayList<>();
        for (int i = 0; i < PLAYERS_PER_SOCIETY; i++) {
            // The 12th player is the third admin (dual role): same name and email as their login.
            String name = i == PLAYERS_PER_SOCIETY - 1 ? admin3.getFullName() : sp.players()[i];
            PlayerRole role = PlayerRole.values()[i % PlayerRole.values().length];
            boolean sold = TEAM_OF[i] >= 0;
            Player p = new Player();
            p.setSociety(society);
            p.setName(name);
            p.setEmail(i == PLAYERS_PER_SOCIETY - 1 ? admin3.getEmail() : emailOf(name, sp.domain()));
            p.setPhone(phone());
            p.setDateOfBirth(LocalDate.of(1984 + (i * 3) % 18, 1 + (i * 5) % 12, 1 + (i * 7) % 27));
            p.setFlatNumber((char) ('A' + i % 4) + "-" + (101 + i * 3));
            p.setAddress(sp.name() + ", Block " + (char) ('A' + i % 4));
            p.setRole(role);
            p.setBattingStyle(i % 3 == 0 ? BattingStyle.LEFT_HAND : BattingStyle.RIGHT_HAND);
            p.setBowlingStyle(bowling(role, i));
            p.setJerseyNumber(7 + i * 3);
            p.setBasePrice(BASE_PRICE);
            p.setMatchesPlayed(sold ? (i % 2 == 0 ? 4 : 3) : 0);
            p.setRuns(sold ? 25 + (i * 37) % 160 : 0);
            p.setWickets(sold && (role == PlayerRole.BOWLER || role == PlayerRole.ALL_ROUNDER) ? 1 + (i * 3) % 7 : 0);
            out.add(players.save(p));
        }
        return out;
    }

    private static BowlingStyle bowling(PlayerRole role, int i) {
        return switch (role) {
            case BOWLER -> i % 2 == 0 ? BowlingStyle.RIGHT_ARM_FAST : BowlingStyle.LEFT_ARM_SPIN;
            case ALL_ROUNDER -> BowlingStyle.RIGHT_ARM_MEDIUM;
            default -> BowlingStyle.NONE;
        };
    }

    /** Teams are created after the players because captains are players; the players are linked back afterwards. */
    private List<Team> seedTeams(Society society, Season season, Spec sp, List<Player> roster) {
        List<Team> out = new ArrayList<>();
        for (int t = 0; t < sp.teams().length; t++) {
            Team team = new Team();
            team.setSociety(society);
            team.setSeason(season);
            team.setName(sp.teams()[t][0]);
            team.setShortName(sp.teams()[t][1]);
            team.setColor(sp.teams()[t][2]);
            team.setCaptain(roster.get(t));
            team.setPurseRemaining(TEAM_PURSE);
            out.add(teams.save(team));
        }
        for (int i = 0; i < roster.size(); i++) {
            roster.get(i).setTeam(TEAM_OF[i] >= 0 ? out.get(TEAM_OF[i]) : null);
        }
        players.saveAll(roster);
        return out;
    }

    /** 10 sold lots (mixed online and offline) and 2 pending ones, so the live-auction screens have something to run. */
    private void seedAuction(Society society, Season season, List<Player> roster, List<Team> teamList) {
        double[] spent = new double[teamList.size()];
        for (int i = 0; i < roster.size(); i++) {
            AuctionLot lot = new AuctionLot();
            lot.setSociety(society);
            lot.setSeason(season);
            lot.setPlayer(roster.get(i));
            lot.setBasePrice(BASE_PRICE);
            if (TEAM_OF[i] < 0) {
                lot.setStatus(LotStatus.PENDING);
                lots.save(lot);
                continue;
            }
            Team team = teamList.get(TEAM_OF[i]);
            double price = 2_000.0 + ((i * 7) % 5) * 500.0;
            boolean online = i % 3 != 2;
            lot.setStatus(LotStatus.SOLD);
            lot.setMode(online ? AuctionMode.ONLINE : AuctionMode.OFFLINE);
            lot.setCurrentBid(price);
            lot.setCurrentBidTeam(team);
            lot.setSoldPrice(price);
            lot.setSoldTeam(team);
            lot = lots.save(lot);
            spent[TEAM_OF[i]] += price;
            if (online) {
                AuctionBid bid = new AuctionBid();
                bid.setLot(lot);
                bid.setTeam(team);
                bid.setAmount(price);
                bids.save(bid);
            }
        }
        for (int t = 0; t < teamList.size(); t++) {
            teamList.get(t).setPurseRemaining(TEAM_PURSE - spent[t]);
        }
        teams.saveAll(teamList);
    }

    /** A 4-team round robin: four results (including a tie and an all-out innings) and two fixtures still to play. */
    private void seedMatches(Society society, Season season, List<Team> teamList) {
        LocalDateTime now = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0);
        for (int m = 0; m < PAIRS.length; m++) {
            Team a = teamList.get(PAIRS[m][0]);
            Team b = teamList.get(PAIRS[m][1]);
            Match match = new Match();
            match.setSociety(society);
            match.setSeason(season);
            match.setTeamA(a);
            match.setTeamB(b);
            match.setVenue("Society Clubhouse Ground");
            match.setStage(MatchStage.LEAGUE);
            if (m < SCORES.length) {
                int[] s = SCORES[m];
                match.setMatchDate(now.minusDays(DAYS_AGO[m]).withHour(18));
                match.setStatus(MatchStatus.COMPLETED);
                match.setTeamARuns(s[0]);
                match.setTeamAWickets(s[1]);
                match.setTeamAOvers(OVERS[m][0]);
                match.setTeamBRuns(s[2]);
                match.setTeamBWickets(s[3]);
                match.setTeamBOvers(OVERS[m][1]);
                Team winner = s[0] > s[2] ? a : s[0] < s[2] ? b : null;
                match.setWinnerTeam(winner);
                match.setResultSummary(String.format(Locale.ROOT, "%s %d/%d (%.1f ov) vs %s %d/%d (%.1f ov) - %s",
                        a.getName(), s[0], s[1], OVERS[m][0], b.getName(), s[2], s[3], OVERS[m][1],
                        winner == null ? "match tied" : winner.getName() + " won"));
            } else {
                match.setMatchDate(now.plusDays(DAYS_AHEAD[m - SCORES.length]).withHour(17));
                match.setStatus(MatchStatus.SCHEDULED);
            }
            matches.save(match);
        }
    }

    // ------------------------------------------------------------------ content

    private void seedSponsorsAwardsGallery(Society society, Season season, Spec sp, List<Player> roster, List<Team> teamList) {
        double[] amounts = {50_000, 25_000, 10_000};
        for (int i = 0; i < sp.sponsors().length; i++) {
            Sponsor s = new Sponsor();
            s.setSociety(society);
            s.setSeason(season);
            s.setName(sp.sponsors()[i][0]);
            s.setTier(SponsorTier.valueOf(sp.sponsors()[i][1]));
            s.setWebsite("https://example.com");
            s.setAmount(amounts[i]);
            s.setActive(true);
            sponsors.save(s);
        }
        award(society, season, "Orange Cap (most runs)", AwardCategory.BEST_BATSMAN,
                "Highest run-scorer of the league so far.", roster.get(1), null);
        award(society, season, "Purple Cap (most wickets)", AwardCategory.BEST_BOWLER,
                "Most wickets of the league so far.", roster.get(5), null);
        award(society, season, "Fair Play Award", AwardCategory.FAIR_PLAY,
                "Voted by the umpires.", null, teamList.get(2));

        String[][] pics = {{"Opening ceremony", "Captains with the trophy."},
                {"Auction night", "The hall auction in full swing."}, {"Team photos", "All four squads before round one."}};
        for (int i = 0; i < pics.length; i++) {
            GalleryImage g = new GalleryImage();
            g.setSociety(society);
            g.setSeason(season);
            g.setTitle(pics[i][0]);
            g.setCaption(pics[i][1]);
            g.setImageUrl("/images/gallery-" + (i + 1) + ".svg"); // shipped with the static assets in Phase 10
            gallery.save(g);
        }
    }

    private void award(Society society, Season season, String title, AwardCategory category, String description,
                       Player player, Team team) {
        Award a = new Award();
        a.setSociety(society);
        a.setSeason(season);
        a.setTitle(title);
        a.setCategory(category);
        a.setDescription(description);
        a.setPlayer(player);
        a.setTeam(team);
        awards.save(a);
    }

    private void seedVendorsAndBills(Society society, Season season, Spec sp, User admin1) {
        List<VendorContact> saved = new ArrayList<>();
        for (int i = 0; i < sp.vendors().length; i++) {
            String[] v = sp.vendors()[i];
            VendorContact vc = new VendorContact();
            vc.setSociety(society);
            vc.setName(v[0]);
            vc.setCategory(VendorCategory.valueOf(v[1]));
            vc.setContactPerson(v[2]);
            vc.setCity(v[3]);
            vc.setPhone(phone());
            vc.setWhatsappNumber(vc.getPhone());
            vc.setEmail("hello@" + v[0].toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "") + ".example");
            vc.setRating(new int[]{4, 5, 3, 4, 5}[i % 5]);
            vc.setNotes("Seeded demo vendor.");
            vc.setActive(true);
            saved.add(vendors.save(vc));
        }
        for (int i = 0; i < sp.bills().length; i++) {
            String[] b = sp.bills()[i];
            BillStatus status = BillStatus.valueOf(b[3]);
            VendorBill bill = new VendorBill();
            bill.setSociety(society);
            bill.setVendor(saved.get(Integer.parseInt(b[0])));
            bill.setSeason(season);
            bill.setTitle(b[1]);
            bill.setAmount(Double.parseDouble(b[2]));
            bill.setBillDate(LocalDate.now().minusDays(18 - i * 5L));
            bill.setStatus(status);
            if (status == BillStatus.PAID) {
                bill.setPaidDate(LocalDate.now().minusDays(8));
                bill.setApprovedBy(admin1);
            }
            bill.setDescription("Seeded demo bill.");
            bills.save(bill);
        }
    }

    /** Ten pending sign-ups in an open season: select all and bulk-approve to see ONE batch approval (§8). */
    private void seedWinterCupRegistrations(Society society) {
        Season winter = season(society, "Winter Cup 2027", 2027, SeasonStatus.REGISTRATION_OPEN, true);
        String[] names = {"Tarun Bhatia", "Isha Khanna", "Ravi Menon", "Pallavi Joshi", "Gaurav Sinha", "Nisha Pillai",
                "Omkar Jadhav", "Zoya Ansari", "Kunal Shetty", "Lakshmi Iyer"};
        for (int i = 0; i < names.length; i++) {
            PlayerRole role = PlayerRole.values()[i % PlayerRole.values().length];
            Registration r = new Registration();
            r.setSociety(society);
            r.setSeason(winter);
            r.setFullName(names[i]);
            r.setEmail(emailOf(names[i], "greenvalley.example"));
            r.setPhone(phone());
            r.setFlatNumber("C-" + (201 + i * 2));
            r.setRole(role);
            r.setBattingStyle(i % 2 == 0 ? BattingStyle.RIGHT_HAND : BattingStyle.LEFT_HAND);
            r.setBowlingStyle(bowling(role, i));
            r.setStatus(RegistrationStatus.PENDING);
            registrations.save(r);
        }
    }

    // ------------------------------------------------------------------ platform events

    private void seedEvents(Society gv, Society sh) {
        LocalDateTime now = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0);
        event("SCPL Season Kick-off Weekend", "Opening matches across all societies.", now.plusDays(14).withHour(9),
                EventType.TOURNAMENT, null, 1);
        event("Early-bird pricing for new societies", "Start your 14-day free trial and pick a plan afterwards.",
                now.plusDays(5).withHour(10), EventType.REGISTRATION_OPEN, null, 2);
        event("Inter-society auction night (live stream)", "Watch the player auction live.", now.plusDays(21).withHour(19),
                EventType.AUCTION, null, 3);
        event("SCPL Awards Gala", "Best batsman, best bowler and fair play awards.", now.plusDays(45).withHour(18),
                EventType.AWARDS, null, 1);
        event("Winter Cup registrations open", "Sign up on the Green Valley page.", now.plusDays(7).withHour(10),
                EventType.REGISTRATION_OPEN, gv, 2);
        event("Sunrise Heights derby", "Strikers take on the Dragons.", now.plusDays(9).withHour(17),
                EventType.MATCH, sh, 3);
    }

    private void event(String title, String description, LocalDateTime when, EventType type, Society society, int image) {
        PlatformEvent e = new PlatformEvent();
        e.setTitle(title);
        e.setDescription(description);
        e.setEventDate(when);
        e.setScope(society == null ? EventScope.PLATFORM : EventScope.SOCIETY);
        e.setSociety(society);
        e.setType(type);
        e.setThumbnailUrl("/images/event-" + image + ".svg"); // shipped with the static assets in Phase 10
        e.setExternalLink("https://www.youtube.com/");
        e.setPublished(true);
        events.save(e);
    }

    // ------------------------------------------------------------------ sample import batches

    private void seedImportBatches(Society society, User uploader, Spec sp, List<Player> roster, boolean greenValley) {
        if (greenValley) {
            playerBatch(society, uploader, sp.domain(), "new-players-october.csv", new String[][]{
                            {"Pranav Kulkarni", "ALL_ROUNDER", "RIGHT_HAND", "RIGHT_ARM_MEDIUM"},
                            {"Tanmay Gokhale", "BATSMAN", "LEFT_HAND", "NONE"},
                            {"Shreya Bhosale", "BOWLER", "RIGHT_HAND", "LEFT_ARM_SPIN"},
                            {"Omkar Phadke", "WICKET_KEEPER", "RIGHT_HAND", "NONE"}},
                    // Same email as an existing player: flagged email_exists and left out by default.
                    new String[]{"Arjun M", roster.get(0).getEmail(), phone(), PlayerRole.BATSMAN.getLabel(),
                            BattingStyle.RIGHT_HAND.getLabel(), BowlingStyle.NONE.getLabel()}, "email_exists");
            vendorBatch(society, uploader, "vendor-list.csv", new String[][]{
                            {"Spice Route Caterers", "FOOD_BEVERAGE", "Mrs. Iyer", "Pune", "4"},
                            {"ClickCraft Photography", "CAMERAMAN", "Rahul", "Pune", "5"},
                            {"SafeSide Medical Services", "MEDICAL_STAFF", "Dr. Naik", "Pune", "4"}},
                    new String[]{sp.vendors()[0][0], VendorCategory.VENUE.getLabel(), "Mr. Pawar", "Pune", "4"});
        } else {
            playerBatch(society, uploader, sp.domain(), "residents-signup-sheet.csv", new String[][]{
                            {"Aarav Trivedi", "BATSMAN", "RIGHT_HAND", "NONE"},
                            {"Myra Fernandes", "ALL_ROUNDER", "LEFT_HAND", "LEFT_ARM_MEDIUM"},
                            {"Zubin Irani", "BOWLER", "RIGHT_HAND", "RIGHT_ARM_FAST"},
                            {"Leela Narayan", "WICKET_KEEPER", "RIGHT_HAND", "NONE"}},
                    // No email, same name as an existing player: flagged duplicate_name.
                    new String[]{roster.get(2).getName(), "", phone(), PlayerRole.BATSMAN.getLabel(),
                            BattingStyle.RIGHT_HAND.getLabel(), BowlingStyle.NONE.getLabel()}, "duplicate_name");
        }
    }

    private void playerBatch(Society society, User uploader, String domain, String fileName, String[][] defs,
                             String[] conflictRow, String conflictCode) {
        List<String[]> source = new ArrayList<>();
        List<ReviewRow> review = new ArrayList<>();
        int line = 2; // line 1 of the source file is the header
        for (String[] d : defs) {
            String[] row = {d[0], emailOf(d[0], domain), phone(), PlayerRole.valueOf(d[1]).getLabel(),
                    BattingStyle.valueOf(d[2]).getLabel(), BowlingStyle.valueOf(d[3]).getLabel()};
            source.add(row);
            review.add(new ReviewRow(ImportKind.PLAYER, fileName, line++, true, playerFields(row), 90, null));
        }
        source.add(conflictRow);
        review.add(new ReviewRow(ImportKind.PLAYER, fileName, line, false, playerFields(conflictRow), 70, conflictCode));
        writeBatch(society, uploader, fileName, ImportKind.PLAYER,
                new String[]{"Full Name", "Email Address", "Mobile No.", "Playing Role", "Batting", "Bowling"}, source, review);
    }

    private void vendorBatch(Society society, User uploader, String fileName, String[][] defs, String[] conflictRow) {
        List<String[]> source = new ArrayList<>();
        List<ReviewRow> review = new ArrayList<>();
        int line = 2;
        for (String[] d : defs) {
            String[] row = {d[0], VendorCategory.valueOf(d[1]).getLabel(), d[2], phone(), "", d[3], d[4]};
            row[4] = row[3];
            source.add(row);
            review.add(new ReviewRow(ImportKind.VENDOR, fileName, line++, true, vendorFields(row), 90, null));
        }
        String[] dup = {conflictRow[0], conflictRow[1], conflictRow[2], phone(), "", conflictRow[3], conflictRow[4]};
        dup[4] = dup[3];
        source.add(dup);
        review.add(new ReviewRow(ImportKind.VENDOR, fileName, line, false, vendorFields(dup), 70, "vendor_exists"));
        writeBatch(society, uploader, fileName, ImportKind.VENDOR,
                new String[]{"Vendor Name", "Service", "Contact", "Phone", "WhatsApp", "City", "Rating"}, source, review);
    }

    private static Map<String, String> playerFields(String[] r) {
        Map<String, String> f = new LinkedHashMap<>();
        put(f, "name", r[0]);
        put(f, "email", r[1]);
        put(f, "phone", r[2]);
        put(f, "role", r[3]);
        put(f, "battingStyle", r[4]);
        put(f, "bowlingStyle", r[5]);
        return f;
    }

    private static Map<String, String> vendorFields(String[] r) {
        Map<String, String> f = new LinkedHashMap<>();
        put(f, "name", r[0]);
        put(f, "category", r[1]);
        put(f, "contactPerson", r[2]);
        put(f, "phone", r[3]);
        put(f, "whatsapp", r[4]);
        put(f, "city", r[5]);
        put(f, "rating", r[6]);
        return f;
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    /** Stores the batch row, then writes original.csv and review.csv where ImportService expects them. */
    private void writeBatch(Society society, User uploader, String fileName, ImportKind kind, String[] headers,
                            List<String[]> sourceRows, List<ReviewRow> review) {
        String sourceCsv = csv(headers, sourceRows);
        Map<String, Integer> conflicts = new LinkedHashMap<>();
        review.stream().filter(r -> r.conflict() != null).forEach(r -> conflicts.merge(r.conflict(), 1, Integer::sum));
        int withConflicts = conflicts.values().stream().mapToInt(Integer::intValue).sum();
        SegregationReport report = new SegregationReport("rule-based", review.size(), Map.of(kind.getLabel(), review.size()),
                conflicts, withConflicts, List.of(new TableSummary(fileName, kind, 85, review.size(), List.of())), List.of());

        UploadBatch b = new UploadBatch();
        b.setSociety(society);
        b.setUploadedBy(uploader);
        b.setOriginalFileName(fileName);
        b.setFileSizeBytes((long) sourceCsv.getBytes(StandardCharsets.UTF_8).length);
        b.setMimeType("text/csv");
        b.setStatus(BatchStatus.READY_FOR_REVIEW);
        b.setReviewDeadline(LocalDateTime.now().plusDays(14));
        try {
            b.setSegregationReport(mapper.writeValueAsString(report));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise the seed report", e);
        }
        b = batches.save(b);
        b.setReviewFileUrl("/society-admin/import/" + b.getId() + "/review");

        Path dir = Paths.get(uploadsDir).toAbsolutePath().normalize()
                .resolve(String.valueOf(society.getId())).resolve(String.valueOf(b.getId()));
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("original.csv"), sourceCsv, StandardCharsets.UTF_8);
            ReviewCsv.write(dir.resolve("review.csv"), review);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the sample import files", e); // rolls the whole seed back
        }
        notifications.notifyAdmins(society.getId(), null, "Import ready for review",
                fileName + ": " + review.size() + " rows found, " + withConflicts + " with conflicts.",
                "/society-admin#import");
    }

    private static String csv(String[] headers, List<String[]> rows) {
        StringWriter out = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT)) {
            printer.printRecord((Object[]) headers);
            for (String[] row : rows) {
                printer.printRecord((Object[]) row);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ small helpers

    private User newUser(String username, String email, String fullName, PlatformRole role, Society society, Player player) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(email);
        u.setFullName(fullName);
        u.setPhone(phone());
        u.setPasswordHash(passwordHash);
        u.setRole(role);
        u.setSociety(society);
        u.setPlayer(player);
        u.setEnabled(true);
        return u;
    }

    /** Fake numbers, in the same format the forms accept. */
    private String phone() {
        return String.format(Locale.ROOT, "98220 %05d", ++phoneCounter);
    }

    private static String emailOf(String name, String domain) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", "").trim().replaceAll("\\s+", ".") + "@" + domain;
    }

    private void logCredentials() {
        log.info("Demo logins (password for all: {})", PASSWORD);
        log.info("  platformadmin  PLATFORM_ADMIN");
        log.info("  gv.admin1, gv.admin2, gv.admin3  SOCIETY_ADMIN (Green Valley, STANDARD plan)");
        log.info("  sh.admin1, sh.admin2, sh.admin3  SOCIETY_ADMIN (Sunrise Heights, trial)");
        log.info("  gv.player1  SOCIETY_PLAYER (Green Valley, captain of Valley Vipers)");
        log.info("  sh.player1  SOCIETY_PLAYER (Sunrise Heights, captain of Sunrise Strikers)");
    }

    // ------------------------------------------------------------------ the two societies

    private static Spec greenValley() {
        return new Spec("Green Valley", "green-valley", "Pune", "Green Valley Residency, Baner Road",
                "A friendly residential society with a weekday-evening cricket league.", "gv", "greenvalley.example", true,
                "Premier League 2026", 2026,
                new String[][]{{"Anil Verma", "President"}, {"Sunita Rao", "Secretary"}, {"Sameer Joshi", "Treasurer"}},
                new String[]{"Arjun Mehta", "Rohan Iyer", "Kabir Shah", "Vikram Rao", "Siddharth Nair", "Aditya Kulkarni",
                        "Rahul Deshmukh", "Karan Malhotra", "Priya Sharma", "Ananya Reddy", "Manish Patil"},
                new String[][]{{"Valley Vipers", "VV", "#2e7d32"}, {"Green Giants", "GG", "#1565c0"},
                        {"Lake Lions", "LL", "#f9a825"}, {"Hilltop Hawks", "HH", "#c62828"}},
                new String[][]{{"Greenfield Realty", "TITLE"}, {"FreshBasket Grocers", "GOLD"}, {"Valley Dental Care", "SILVER"}},
                new String[][]{{"Clubhouse Ground Booking", "VENUE", "Mr. Pawar", "Pune"},
                        {"PrintPro Jerseys", "TSHIRT_PRINTING", "Neelam", "Pune"},
                        {"BrightBeam Lighting", "LIGHTING", "Salim", "Pune"},
                        {"CrickUmp Association", "UMPIRE", "Mr. Desai", "Pune"},
                        {"Golden Trophy House", "TROPHIES", "Mrs. Kale", "Pune"}},
                new String[][]{{"0", "Ground booking - league fixtures", "25000", "PAID"},
                        {"1", "Team jerseys (60 sets)", "18500", "PENDING"},
                        {"3", "Umpire fees - first round", "6000", "DISPUTED"}});
    }

    private static Spec sunriseHeights() {
        return new Spec("Sunrise Heights", "sunrise-heights", "Pune", "Sunrise Heights, Kharadi",
                "A newer society trialling SCPL for its first season.", "sh", "sunriseheights.example", false,
                "Sunrise Cup 2026", 2026,
                new String[][]{{"Deepak Menon", "President"}, {"Kavita Singh", "Secretary"}, {"Farhan Qureshi", "Treasurer"}},
                new String[]{"Rishi Bhatt", "Tanvi Desai", "Yash Kapoor", "Nikhil Pawar", "Meera Nambiar", "Harsh Vora",
                        "Ishaan Gupta", "Pooja Chopra", "Aman Saxena", "Dhruv Jain", "Sneha Bose"},
                new String[][]{{"Sunrise Strikers", "SS", "#ef6c00"}, {"Dawn Dragons", "DD", "#6a1b9a"},
                        {"Morning Mavericks", "MM", "#00838f"}, {"Skyline Stars", "SK", "#ad1457"}},
                new String[][]{{"Metro Cycles", "TITLE"}, {"BrightPath Tutors", "GOLD"}, {"Daily Dairy Co-op", "SILVER"}},
                new String[][]{{"Lakeside Catering", "FOOD_BEVERAGE", "Mrs. Rao", "Pune"},
                        {"Frame Moments Studio", "CAMERAMAN", "Imran", "Pune"},
                        {"Festive Decor Works", "DECORATION", "Anita", "Pune"},
                        {"CareFirst Medical Aid", "MEDICAL_STAFF", "Dr. Shah", "Pune"},
                        {"Spark Electricals", "ELECTRICIAN", "Mr. Gill", "Pune"}},
                new String[][]{{"0", "Match-day lunch (round 1)", "12000", "PAID"},
                        {"1", "Photography package", "9000", "PENDING"},
                        {"2", "Stage and banner decoration", "7500", "DISPUTED"}});
    }
}