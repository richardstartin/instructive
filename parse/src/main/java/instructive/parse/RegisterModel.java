package instructive.parse;

import instructive.parse.aarch64.Aarch64RegisterModel;
import instructive.parse.x86.X86RegisterModel;
import java.util.Locale;
import java.util.Optional;

/** Which registers instructions of one architecture read and write. */
public interface RegisterModel {

    /** The registers {@code instruction} reads and writes; {@link RegisterUsage#UNKNOWN} if it wasn't decoded. */
    RegisterUsage usage(Instruction instruction);

    /** The register model for an {@code os.arch} value, if there is one. */
    static Optional<RegisterModel> forArchitecture(String arch) {
        return switch (arch.toLowerCase(Locale.ROOT)) {
            case "aarch64", "arm64" -> Optional.of(new Aarch64RegisterModel());
            case "amd64", "x86_64" -> Optional.of(new X86RegisterModel());
            default -> Optional.empty();
        };
    }
}
