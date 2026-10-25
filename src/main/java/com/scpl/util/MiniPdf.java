package com.scpl.util;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * One-page text PDF for the mock invoices (§12), hand-built to avoid a PDF dependency for
 * something this small. Output is pure ASCII, so character count equals byte count and the
 * xref offsets can be taken straight from the StringBuilder.
 */
public final class MiniPdf {

    private MiniPdf() {
    }

    public static byte[] render(String title, List<String> lines) {
        StringBuilder content = new StringBuilder();
        content.append("BT\n/F1 18 Tf\n50 790 Td\n24 TL\n(").append(escape(title)).append(") Tj\n")
                .append("/F1 11 Tf\n16 TL\n");
        for (String line : lines) {
            content.append("T* (").append(escape(line)).append(") Tj\n");
        }
        content.append("ET");

        String[] objects = {
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] "
                        + "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
                "<< /Length " + content.length() + " >>\nstream\n" + content + "\nendstream"
        };

        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        int[] offsets = new int[objects.length];
        for (int i = 0; i < objects.length; i++) {
            offsets[i] = pdf.length();
            pdf.append(i + 1).append(" 0 obj\n").append(objects[i]).append("\nendobj\n");
        }
        int xref = pdf.length();
        pdf.append("xref\n0 ").append(objects.length + 1).append("\n0000000000 65535 f \n");
        for (int offset : offsets) {
            pdf.append(String.format(Locale.ROOT, "%010d 00000 n \n", offset));
        }
        pdf.append("trailer\n<< /Size ").append(objects.length + 1).append(" /Root 1 0 R >>\nstartxref\n")
                .append(xref).append("\n%%EOF\n");
        return pdf.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /** Escapes PDF string delimiters and replaces anything outside printable ASCII. */
    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : (s == null ? "" : s).toCharArray()) {
            if (c == '\\' || c == '(' || c == ')') {
                sb.append('\\').append(c);
            } else if (c >= 32 && c <= 126) {
                sb.append(c);
            } else {
                sb.append('?');
            }
        }
        return sb.toString();
    }
}