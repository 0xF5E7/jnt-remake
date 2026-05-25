package war.metaphor;

import war.configuration.ConfigurationSection;
import war.jar.JarReader;
import war.metaphor.base.ObfuscatorContext;
import war.metaphor.mutator.integer.*;
import war.metaphor.mutator.data.strings.*;
import war.metaphor.mutator.data.strings.poly2.*;
import war.metaphor.mutator.flow.*;
import war.metaphor.mutator.rename.*;
import war.metaphor.mutator.integrity.CallGraphIntegrityMutator;
import war.metaphor.mutator.anti.*;
import war.metaphor.mutator.integrity.mainCallCheck.MainCallCheckMutator;
import war.metaphor.mutator.integrity.method.MethodIntegrityMutator;
import war.metaphor.mutator.loader.*;
import war.metaphor.mutator.misc.*;
import war.metaphor.mutator.optimization.OptimizationMutator;
import war.metaphor.mutator.optimization.UnusedClassMutator;
import war.metaphor.mutator.optimization.UnusedMethodMutator;
import war.metaphor.mutator.parameter.ExchangeMutator;
import war.metaphor.mutator.ref.ReferenceMutator;
import war.metaphor.mutator.runtime.RuntimePatchMutator;
import war.metaphor.mutator.splash.SplashScreenMutator;
import war.metaphor.mutator.virtualization.VirtualizingTransformer;

import java.nio.file.Path;

public class Metaphor {

    public ObfuscatorContext buildObfuscatePass(JarReader intake, ConfigurationSection cfg, String dir) {
        return ObfuscatorContext.builder()
                .input(intake.getInput().toPath())
                .output(Path.of(String.format("%s/metaphor-temp.jar", dir)))
                .mappings(cfg.getStringList("mappings"))
                .section("mutators.metaphor")
                .config(cfg)
                .classes(intake.getClasses())
                .libraries(intake.getLibraries())
                .resources(intake.getResources())
                .manifest(intake.getManifest())

                .mutator("method-call-fix", MethodCallFixer.class)
                .mutator("bootstrap-entry", BootstrapEntryMutator.class)

                .mutator("unused-method-remover", UnusedMethodMutator.class)
                .mutator("unused-class-remover", UnusedClassMutator.class)

                .mutator("optimizer", OptimizationMutator.class)
                .mutator("inlining", MethodInliningMutator.class)
                .mutator("field-initialize", FieldInlinerMutator.class)
                .mutator("access-unify", AccessUnifyMutator.class)

                .mutator("internal-class-integrator", InternalClassIntegrateMutator.class)

                .mutator("renamer.class", ClassRenameMutator.class)
                .mutator("renamer.method", MethodRenameMutator.class)
                .mutator("renamer.field", FieldRenameMutator.class)
                .mutator("renamer.desc", DescriptorMutator.class)

                .mutator("main-call-check", MainCallCheckMutator.class)
                .mutator("call-graph", CallGraphIntegrityMutator.class)
                .mutator("method-integrity", MethodIntegrityMutator.class)
            
                .mutator("anti-debug",  AntiDebugTransformer.class) 
                .mutator("anti-tamper", AntiTamperTransformer.class)
                .mutator("anti-dump", AntiDumpTransformer.class)

                .mutator("string.poly", StringTransformer.class)
                .mutator("string.poly2", NewStringTransformer.class)
                .mutator("string.light", LightStringTransformer.class)
                .mutator("string.split", StringSplitTransformer.class)
                .mutator("string.stack", StringStackTransformer.class)
                .mutator("ahegao", AhegaoTransformer.class)
            
                .mutator("flow.break", BlockBreakMutator.class)
                .mutator("flow.flattening", ControlFlowFlatteningMutator.class)
                .mutator("method-split",    MethodSplittingMutator.class)
                .mutator("flow.shuffle", InstructionShuffleMutator.class)
                .mutator("flow.switch", SwitchMutator.class)
                .mutator("flow.traps", TrapEdgeMutator.class)
                .mutator("flow.opaque", OpaquePredicatesMutator.class)
            
                .mutator("number.salt", SaltingIntegerTransformer.class)
                .mutator("number.table", IntegerTableTransformer.class)
                .mutator("mba", MBATransformer.class)
                .mutator("dead-code",  DeadCodeInjectorTransformer.class)
                .mutator("virtualize",  VirtualizingTransformer.class)  
                

                .mutator("ref", ReferenceMutator.class)
                .mutator("var-duplicate", VarDuplicateTransformer.class)

                .mutator("lift-constructors", LiftInitializersMutator.class)

                .mutator("watermark", WatermarkMutator.class)

                .mutator("strip", StripMutator.class)

                .mutator("dot-graph", DotExportMutator.class)

                .mutator("indy-rewriter", IndyTransformer.class)
                .mutator("class-split", ClassSplittingTransformer.class)

                .mutator("splash-screen", SplashScreenMutator.class)

                //.mutator("goto-to-jsr", GotoToJsrMutator.class)
                .mutator("array-rewriter", MultiNewArrayMutator.class)

                .mutator("runtime-patch", RuntimePatchMutator.class)
                .mutator("exchange", ExchangeMutator.class)
                .build();
    }

    public ObfuscatorContext buildPackagePass(JarReader intake, ConfigurationSection cfg, String dir) {
        return ObfuscatorContext.builder()
                .input(intake.getInput().toPath())
                .output(Path.of(String.format("%s/output-final.jar", dir)))
                .section("mutators.jnt")
                .config(cfg)
                .classes(intake.getClasses())
                .libraries(intake.getLibraries())
                .resources(intake.getResources())
                .manifest(intake.getManifest())
                .mutator("cleanup", CleanupMutator.class)
                .mutator("integrate", IntegrateLoaderMutator.class)
                .build();
    }
}
