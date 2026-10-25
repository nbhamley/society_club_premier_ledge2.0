package com.scpl.util;

import com.scpl.dto.ImportKind;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The importable fields of each kind and the header names that map to them. Canonical field names
 * are also the column headers of review.csv. Aliases are compared after normalisation, so
 * "Mobile No." and "mobile_no" are the same header.
 */
public final class ImportSchema {

    public record Spec(List<String> fields, Set<String> required, Map<String, String> aliases) {
    }

    /** Column order of review.csv: the union of every kind's fields. */
    public static final List<String> ALL_FIELDS = List.of(
            "name", "title", "vendor", "season", "email", "phone", "dateOfBirth", "flatNumber", "address",
            "role", "battingStyle", "bowlingStyle", "jerseyNumber", "basePrice", "shortName", "color",
            "teamA", "teamB", "date", "venue", "stage", "category", "description", "player", "team",
            "contactPerson", "whatsapp", "city", "website", "rating", "notes", "amount", "billDate");

    private static final Map<String, String> FIELD_BY_NORM = new HashMap<>();
    private static final Map<ImportKind, Spec> SPECS = new EnumMap<>(ImportKind.class);

    static {
        for (String f : ALL_FIELDS) {
            FIELD_BY_NORM.put(ImportValues.normalize(f), f);
        }
        SPECS.put(ImportKind.PLAYER, spec(
                new String[]{"name", "email", "phone", "dateOfBirth", "flatNumber", "address", "role",
                        "battingStyle", "bowlingStyle", "jerseyNumber", "basePrice"},
                new String[]{"name"},
                new String[][]{
                        {"name", "fullname", "playername", "player", "membername"},
                        {"email", "emailaddress", "mail", "emailid"},
                        {"phone", "mobile", "mobilenumber", "mobileno", "contact", "contactnumber", "phonenumber"},
                        {"dateOfBirth", "dob", "birthdate", "birthday"},
                        {"flatNumber", "flat", "flatno", "apartment", "unit"},
                        {"role", "playingrole", "playertype", "type"},
                        {"battingStyle", "batting", "bat"},
                        {"bowlingStyle", "bowling", "bowl"},
                        {"jerseyNumber", "jersey", "jerseyno", "shirtnumber"},
                        {"basePrice", "price", "baseamount"}}));
        SPECS.put(ImportKind.TEAM, spec(
                new String[]{"name", "shortName", "color", "season"},
                new String[]{"name"},
                new String[][]{
                        {"name", "teamname", "team"},
                        {"shortName", "short", "abbreviation", "code"},
                        {"color", "colour", "teamcolor", "teamcolour"},
                        {"season", "seasonname"}}));
        SPECS.put(ImportKind.MATCH, spec(
                new String[]{"season", "teamA", "teamB", "date", "venue", "stage"},
                new String[]{"teamA", "teamB"},
                new String[][]{
                        {"season", "seasonname"},
                        {"teamA", "team1", "hometeam", "home"},
                        {"teamB", "team2", "awayteam", "away"},
                        {"date", "matchdate", "datetime", "when", "schedule"},
                        {"venue", "ground", "location"},
                        {"stage", "round", "matchtype"}}));
        SPECS.put(ImportKind.AWARD, spec(
                new String[]{"title", "category", "description", "season", "player", "team"},
                new String[]{"title"},
                new String[][]{
                        {"title", "award", "awardname", "name"},
                        {"category", "awardcategory", "type"},
                        {"description", "details", "notes"},
                        {"season", "seasonname"},
                        {"player", "playername", "winner"},
                        {"team", "teamname"}}));
        SPECS.put(ImportKind.VENDOR, spec(
                new String[]{"name", "category", "contactPerson", "phone", "whatsapp", "email", "address", "city",
                        "website", "rating", "notes"},
                new String[]{"name"},
                new String[][]{
                        {"name", "vendor", "vendorname", "company", "business"},
                        {"category", "vendorcategory", "type", "service"},
                        {"contactPerson", "contact", "person", "contactname"},
                        {"phone", "mobile", "telephone", "phonenumber"},
                        {"whatsapp", "whatsappnumber", "wa"},
                        {"email", "emailaddress", "mail"},
                        {"city", "town"},
                        {"website", "web", "url", "site"},
                        {"rating", "stars"},
                        {"notes", "remarks", "comments"}}));
        SPECS.put(ImportKind.BILL, spec(
                new String[]{"vendor", "title", "amount", "season", "billDate", "description"},
                new String[]{"vendor", "title", "amount"},
                new String[][]{
                        {"vendor", "vendorname", "supplier", "payee"},
                        {"title", "bill", "billtitle", "item", "particulars", "name"},
                        {"amount", "total", "cost", "price", "billamount"},
                        {"season", "seasonname"},
                        {"billDate", "date", "invoicedate"},
                        {"description", "notes", "details", "remarks"}}));
    }

    private ImportSchema() {
    }

    private static Spec spec(String[] fields, String[] required, String[][] aliasRows) {
        Map<String, String> aliases = new HashMap<>();
        for (String f : fields) {
            aliases.put(ImportValues.normalize(f), f);
        }
        for (String[] row : aliasRows) {
            for (int i = 1; i < row.length; i++) {
                aliases.put(ImportValues.normalize(row[i]), row[0]);
            }
        }
        return new Spec(List.of(fields), Set.of(required), Map.copyOf(aliases));
    }

    public static Spec of(ImportKind kind) {
        return SPECS.get(kind);
    }

    /** The canonical field a header means for this kind, or null. */
    public static String canonical(ImportKind kind, String header) {
        return SPECS.get(kind).aliases().get(ImportValues.normalize(header));
    }

    /** Kind-independent lookup for review-file columns, which already use canonical names. */
    public static String fieldByColumnName(String header) {
        return FIELD_BY_NORM.get(ImportValues.normalize(header));
    }

    /** Guess from a file or sheet name: "players.csv" suggests PLAYER. Only a tie-breaking hint. */
    public static ImportKind hint(String sourceName) {
        String n = sourceName == null ? "" : sourceName.toLowerCase();
        if (n.contains("bill") || n.contains("invoice") || n.contains("expense")) {
            return ImportKind.BILL;
        }
        if (n.contains("vendor") || n.contains("supplier")) {
            return ImportKind.VENDOR;
        }
        if (n.contains("player")) {
            return ImportKind.PLAYER;
        }
        if (n.contains("team")) {
            return ImportKind.TEAM;
        }
        if (n.contains("match") || n.contains("fixture")) {
            return ImportKind.MATCH;
        }
        if (n.contains("award")) {
            return ImportKind.AWARD;
        }
        return null;
    }
}