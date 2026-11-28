package instructive.analyse;

import instructive.analyse.aarch64.Aarch64MemoryOrderingModel;
import instructive.analyse.aarch64.Aarch64VectorisationModel;
import instructive.analyse.x86.X86MemoryOrderingModel;
import instructive.analyse.x86.X86VectorisationModel;
import instructive.parse.Disassembler;
import instructive.parse.RegisterModel;
import instructive.parse.aarch64.Aarch64Disassembler;
import instructive.parse.aarch64.Aarch64RegisterModel;
import instructive.parse.x86.X86Disassembler;
import instructive.parse.x86.X86RegisterModel;

/** An architecture's decoder and models, and where its listings are, for rules tested on both. */
enum Architecture {
    AARCH64("", new Aarch64Disassembler(), new Aarch64RegisterModel(), new Aarch64VectorisationModel(),
            new Aarch64MemoryOrderingModel()),
    X86("x86/", new X86Disassembler(), new X86RegisterModel(), new X86VectorisationModel(), new X86MemoryOrderingModel());

    final String directory;
    final Disassembler disassembler;
    final RegisterModel registers;
    final VectorisationModel vectorisation;
    final MemoryOrderingModel ordering;

    Architecture(String directory, Disassembler disassembler, RegisterModel registers, VectorisationModel vectorisation,
            MemoryOrderingModel ordering) {
        this.directory = directory;
        this.disassembler = disassembler;
        this.registers = registers;
        this.vectorisation = vectorisation;
        this.ordering = ordering;
    }

    /** A listing in this architecture's directory. */
    Listing load(String resource) {
        return Listing.load(directory + resource, disassembler);
    }
}
