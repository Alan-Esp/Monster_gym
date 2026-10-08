package com.monstergym.printing;

import java.io.ByteArrayOutputStream;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.text.Normalizer;

/**
 * Convierte un Ticket a bytes ESC/POS para la PT-210.
 * Inicio: ESC @ (inicializar), FS . (salir del modo chino con que arranca) y ESC t n (página de códigos).
 * Fin: 4 saltos de línea. Nunca manda corte (GS V) ni apertura de cajón (ESC p): la PT-210 no tiene.
 * La PT-210 real ignora FS . y sigue en modo chino: con US-ASCII (valor por defecto) nunca se manda un byte >= 0x80.
 */
public final class EscPosEncoder {

    static final byte ESC = 0x1B, FS = 0x1C, GS = 0x1D, LF = 0x0A;
    static final int FEED_LINES = 4;

    private EscPosEncoder() {
    }

    public static byte[] encode(Ticket ticket, int codepage, Charset charset) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        out.writeBytes(new byte[]{ESC, '@'});
        out.writeBytes(new byte[]{FS, '.'});
        out.writeBytes(new byte[]{ESC, 't', (byte) codepage});
        CharsetEncoder encoder = charset.newEncoder();
        for (Ticket.Line line : ticket.lines()) {
            if (line.bold()) out.writeBytes(new byte[]{ESC, 'E', 1});
            if (line.tall()) out.writeBytes(new byte[]{GS, '!', 0x01});  // doble alto, mismo ancho
            out.writeBytes(text(line.text(), encoder));
            if (line.tall()) out.writeBytes(new byte[]{GS, '!', 0x00});
            if (line.bold()) out.writeBytes(new byte[]{ESC, 'E', 0});
            out.write(LF);
        }
        for (int i = 0; i < FEED_LINES; i++) out.write(LF);
        return out.toByteArray();
    }

    /** Codifica el texto; lo que la tabla no tenga se escribe sin acento (á → a, Ñ → N), sin ¿ ¡ o como '?'. */
    static byte[] text(String text, CharsetEncoder encoder) {
        StringBuilder safe = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (c < 0x20) {
                safe.append(' ');  // ningún carácter de control se cuela como comando
            } else if (encoder.canEncode(c)) {
                safe.append(c);
            } else if (c == '¿' || c == '¡') {
                continue;          // en español se entiende sin el signo de apertura
            } else if (c == '·' || c == '—' || c == '–') {
                safe.append(c == '·' ? ',' : '-');
            } else {
                String base = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
                safe.append(base.length() == 1 && encoder.canEncode(base.charAt(0)) ? base.charAt(0) : '?');
            }
        }
        try {
            var buf = encoder.reset().encode(CharBuffer.wrap(safe));
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            return bytes;
        } catch (CharacterCodingException e) {
            throw new IllegalStateException(e);  // no ocurre: todo se verificó con canEncode
        }
    }
}
