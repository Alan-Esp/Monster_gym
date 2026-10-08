package com.monstergym;

import com.monstergym.printing.EscPosEncoder;
import com.monstergym.printing.Ticket;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Bytes ESC/POS para la PT-210: inicio, página de códigos, estilos y final sin corte ni cajón. */
class EscPosEncoderTest {

    private static final Charset CP850 = Charset.forName("IBM850");
    private static final Charset W1252 = Charset.forName("windows-1252");

    private static byte[] encode(Charset cs, int codepage, Ticket.Line... lines) {
        return EscPosEncoder.encode(new Ticket(List.of(lines)), codepage, cs);
    }

    private static byte[] bytes(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < values.length; i++) b[i] = (byte) values[i];
        return b;
    }

    @Test
    void empiezaConInicializarCancelarModoChinoYPaginaDeCodigos() {
        byte[] out = encode(CP850, 2, Ticket.Line.plain("Hola"));
        // ESC @, FS ., ESC t 2
        assertThat(Arrays.copyOfRange(out, 0, 7)).isEqualTo(bytes(0x1B, 0x40, 0x1C, 0x2E, 0x1B, 0x74, 2));
        assertThat(encode(W1252, 16, Ticket.Line.plain("x"))[6]).isEqualTo((byte) 16);
    }

    @Test
    void terminaConCuatroSaltosYSinCorteNiCajon() {
        byte[] out = encode(CP850, 2, Ticket.Line.plain("Hola"));
        assertThat(Arrays.copyOfRange(out, out.length - 5, out.length)).isEqualTo(bytes(0x0A, 0x0A, 0x0A, 0x0A, 0x0A));
        assertThat(indexOf(out, bytes(0x1D, 0x56))).as("GS V (corte)").isEqualTo(-1);
        assertThat(indexOf(out, bytes(0x1B, 0x70))).as("ESC p (cajón)").isEqualTo(-1);
    }

    @Test
    void acentosEnPc850() {
        byte[] out = encode(CP850, 2, Ticket.Line.plain("áéíóúñÑü¿¡$"));
        assertThat(indexOf(out, bytes(0xA0, 0x82, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0x81, 0xA8, 0xAD, 0x24, 0x0A)))
                .isPositive();
    }

    @Test
    void acentosEnWindows1252() {
        byte[] out = encode(W1252, 16, Ticket.Line.plain("áéíóúñÑü¿¡$"));
        assertThat(indexOf(out, bytes(0xE1, 0xE9, 0xED, 0xF3, 0xFA, 0xF1, 0xD1, 0xFC, 0xBF, 0xA1, 0x24, 0x0A)))
                .isPositive();
    }

    @Test
    void enAscii_sinAcentosSinSignosDeAperturaYSinBytesAltos() {
        // La PT-210 real no sale del modo chino: todo byte >= 0x80 se imprime como carácter chino
        byte[] out = encode(Charset.forName("US-ASCII"), 0,
                Ticket.Line.plain("¿Ñandú? ¡Peña! áéíóúü · € — fin"));
        assertThat(indexOf(out, "Nandu? Pena! aeiouu , ? - fin\n".getBytes())).isPositive();
        for (byte b : out) assertThat(b & 0xFF).isLessThan(0x80);
    }

    @Test
    void negritaYDobleAlto_seActivanYDesactivan() {
        byte[] out = encode(CP850, 2, new Ticket.Line("TOTAL", true, true));
        assertThat(indexOf(out, bytes(0x1B, 0x45, 1, 0x1D, 0x21, 1, 'T', 'O', 'T', 'A', 'L', 0x1D, 0x21, 0, 0x1B, 0x45, 0, 0x0A)))
                .isPositive();
    }

    @Test
    void caracteresDeControlEnLosDatos_noSeMandanComoComandos() {
        byte[] out = encode(CP850, 2, Ticket.Line.plain("A\u001BpB\u001DVC"));
        assertThat(indexOf(out, bytes('A', ' ', 'p', 'B', ' ', 'V', 'C'))).isPositive();
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (int i = 0; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
