package qupath.ext.basicstitching.assembly;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bc.zarr.Compressor;
import com.bc.zarr.CompressorFactory;
import java.util.Locale;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import qupath.ext.basicstitching.channel.ResamplePolicy;

/**
 * Names that are matched after a case conversion must survive a Turkish default locale, where
 * {@code "ZLIB".toLowerCase()} is {@code "zlıb"} (dotless i) and matches nothing.
 */
class LocaleIndependenceTest {

    private static <T> T inTurkish(Supplier<T> body) {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            return body.get();
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void upperCaseZlibStaysZlibInTurkish() {
        Compressor compressor = inTurkish(() -> PyramidImageWriter.createZarrCompressor("ZLIB"));
        assertEquals("zlib", ((CompressorFactory.BloscCompressor) compressor).getCname());
    }

    @Test
    void upperCaseLinearIsRecognizedInTurkish() {
        assertEquals(ResamplePolicy.LINEAR, inTurkish(() -> ResamplePolicy.fromDeclared("LINEAR")));
    }
}
