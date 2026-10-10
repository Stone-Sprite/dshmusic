import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.fabricmc.loader.impl.lib.mappingio.MappingReader;
import net.fabricmc.loader.impl.lib.mappingio.tree.MemoryMappingTree;
import net.fabricmc.loader.impl.lib.tinyremapper.IMappingProvider;
import net.fabricmc.loader.impl.lib.tinyremapper.NonClassCopyMode;
import net.fabricmc.loader.impl.lib.tinyremapper.OutputConsumerPath;
import net.fabricmc.loader.impl.lib.tinyremapper.TinyRemapper;
import net.fabricmc.loader.impl.lib.tinyremapper.TinyUtils;

/**
 * Remap a jar between two namespaces, using the tiny-remapper that Fabric Loader
 * already ships.
 *
 * <p>Needed because the mod is written against Mojang names while Fabric loads
 * intermediary ones. Loom normally does this; without Gradle it has to be driven
 * directly.
 *
 * <pre>
 * RemapTool &lt;in.jar&gt; &lt;out.jar&gt; &lt;mappings.tiny&gt; &lt;fromNs&gt; &lt;toNs&gt; [classpath...]
 * </pre>
 */
public final class RemapTool {

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("usage: RemapTool <in> <out> <mappings.tiny> <from> <to> [classpath...]");
            System.exit(2);
        }
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        Path mappings = Path.of(args[2]);
        String from = args[3];
        String to = args[4];

        List<Path> classpath = new ArrayList<>();
        for (int i = 5; i < args.length; i++) {
            classpath.add(Path.of(args[i]));
        }

        System.out.println("reading mappings " + mappings.getFileName() + " …");
        MemoryMappingTree tree = new MemoryMappingTree();
        MappingReader.read(mappings, tree);
        System.out.println("  namespaces: " + tree.getSrcNamespace() + " -> " + tree.getDstNamespaces());
        IMappingProvider provider = TinyUtils.createMappingProvider(tree, from, to);

        TinyRemapper remapper = TinyRemapper.newRemapper((level, msg) -> {
                    // the remapper insists on a logger; surface only what matters
                    if (level == net.fabricmc.loader.impl.lib.tinyremapper.api.TrLogger.Level.ERROR
                            || level == net.fabricmc.loader.impl.lib.tinyremapper.api.TrLogger.Level.WARN) {
                        System.err.println("[remap/" + level + "] " + msg);
                    }
                })
                .withMappings(provider)
                // locals have no mappings here; renaming them would only add noise
                .renameInvalidLocals(false)
                .build();

        if (!classpath.isEmpty()) {
            System.out.println("reading classpath (" + classpath.size() + " entries) …");
            remapper.readClassPathAsync(classpath.toArray(new Path[0])).join();
        }

        System.out.println("remapping " + input.getFileName() + " …");
        try (OutputConsumerPath out = new OutputConsumerPath.Builder(output).build()) {
            out.addNonClassFiles(input, NonClassCopyMode.FIX_META_INF, remapper);
            remapper.readInputsAsync(null, input).join();
            remapper.apply(out);
        }
        remapper.finish();
        System.out.println("wrote " + output + "  (" + java.nio.file.Files.size(output) / 1024 + " KB)");
    }
}
