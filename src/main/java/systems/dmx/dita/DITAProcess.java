package systems.dmx.dita;

import static systems.dmx.dita.Constants.*;

import systems.dmx.core.Topic;
import systems.dmx.core.service.CoreService;
import systems.dmx.topicmaps.TopicmapsService;

import org.dita.dost.Processor;
import org.dita.dost.ProcessorFactory;
import org.dita.dost.util.Configuration;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;



class DITAProcess {

    // ------------------------------------------------------------------------------------------------------- Constants

    private static final File DITA_DIR = resolveDir("dmx.dita.install_dir", "dita-ot");
    private static final File OUTPUT_DIR = resolveDir("dmx.dita.output_dir", "dita-output");
    private static final File TEMP_DIR = resolveDir("dmx.dita.temp_dir", "dita-temp");

    private static final DITAExporter exporter;
    private static final ProcessorFactory pf;

    static {
        ensureDitaOtAvailable();
        exporter = new DITAExporter(TEMP_DIR);
        pf = ProcessorFactory.newInstance(DITA_DIR);
        pf.setBaseTempDir(TEMP_DIR);
    }

    // ---------------------------------------------------------------------------------------------- Instance Variables

    private long processorId;
    private long topicmapId;
    private TopicmapNavigation tmNav;
    private CoreService dmx;

    private Logger logger = Logger.getLogger(getClass().getName());

    // ---------------------------------------------------------------------------------------------------- Constructors

    DITAProcess(long processorId, long topicmapId, TopicmapsService tmService, CoreService dmx) {
        this.processorId = processorId;
        this.topicmapId = topicmapId;
        this.tmNav = new TopicmapNavigation(topicmapId, tmService, dmx);
        this.dmx = dmx;
    }

    // ----------------------------------------------------------------------------------------- Package Private Methods

    void run() {
        // create input files for processor
        List<Topic> sequence = findTopicSequence(processorId);
        logger.info("Topics in sequence: " + sequence.size());
        exporter.export(dmx.getTopic(topicmapId), sequence);
        //
        runProcessor();
    }

    static List<String> getTranstypes() {
        return Configuration.transtypes;
    }

    // ------------------------------------------------------------------------------------------------- Private Methods

    private void runProcessor() {
        ClassLoader currentClassLoader = null;
        try {
            currentClassLoader = Thread.currentThread().getContextClassLoader();
            ClassLoader bundleClassloader = getClass().getClassLoader();
            Thread.currentThread().setContextClassLoader(bundleClassloader);
            logDebugInfo(currentClassLoader, bundleClassloader);
            //
            pf.newProcessor(getOutputFormat())
                .setInput(new File(TEMP_DIR, topicmapId + ".xml"))
                .setOutputDir(OUTPUT_DIR)
                .run();
            //
            logger.info("DITA-OT processing successful");
        } catch (Exception e) {
            throw new RuntimeException("DITA-OT processing failed", e);
        } finally {
            Thread.currentThread().setContextClassLoader(currentClassLoader);
        }
    }

    // ---

    private List<Topic> findTopicSequence(long processorId) {
        List<Topic> sequence = new ArrayList();
        Topic topic = findStartTopic(processorId);
        while (topic != null) {
            sequence.add(topic);
            topic = findNextTopic(topic.getId());
        }
        return sequence;
    }

    private Topic findStartTopic(long processorId) {
        try {
            Topic topic = tmNav.getRelatedTopic(processorId, PROCESSOR_START, ROLE_PROCESSOR, ROLE_START, null);
            if (topic == null) {
                throw new RuntimeException("No start topic defined");
            }
            return topic;
        } catch (Exception e) {
            throw new RuntimeException("Finding start topic failed", e);
        }
    }

    private Topic findNextTopic(long topicId) {
        try {
            return tmNav.getRelatedTopic(topicId, SEQUENCE, ROLE_PREDECESSOR, ROLE_SUCCESSOR, null);
        } catch (Exception e) {
            throw new RuntimeException("Finding next topic in sequence failed", e);
        }
    }

    // ---

    private String getOutputFormat() {
        String outputFormat = dmx.getTopic(processorId).getChildTopics().getString(DITA_OUTPUT_FORMAT, null);
        if (outputFormat == null) {
            throw new RuntimeException("Output format not set");
        }
        return outputFormat;
    }

    // ---

    private void logDebugInfo(ClassLoader currentClassLoader, ClassLoader bundleClassloader) {
        logger.info("org.osgi.framework.bootdelegation=" + System.getProperty("org.osgi.framework.bootdelegation") +
            "\n      org.osgi.framework.system.packages.extra=" +
            System.getProperty("org.osgi.framework.system.packages.extra") +
            "\n      Current ClassLoader=" + currentClassLoader + ", parent=" + currentClassLoader.getParent() +
            "\n      Bundle ClassLoader=" + bundleClassloader + ", parent=" + bundleClassloader.getParent() +
            "\n      Available transtypes=" + Configuration.transtypes);
    }

    // ------------------------------------------------------------------------------------------------- Static helpers

    private static File resolveDir(String property, String fallbackName) {
        String raw = System.getProperty(property, "").trim();
        File dir = raw.isEmpty()
            ? new File(System.getProperty("java.io.tmpdir"), "dmx-dita/" + fallbackName)
            : new File(raw);
        if (!dir.isAbsolute()) {
            dir = dir.getAbsoluteFile();
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Could not create directory " + dir);
        }
        return dir;
    }

    private static void ensureDitaOtAvailable() {
        // If a proper install dir is configured and looks valid, keep it.
        if (new File(DITA_DIR, "config").isDirectory()) {
            return;
        }
        // Otherwise unpack the embedded DITA-OT distribution (dost-3.7.3.jar) into the install dir.
        try (InputStream in = DITAProcess.class.getClassLoader().getResourceAsStream("dost-3.7.3.jar")) {
            if (in == null) {
                throw new IllegalStateException("Embedded DITA-OT (dost-3.7.3.jar) not found on the bundle classpath");
            }
            unzip(in, DITA_DIR);
        } catch (IOException e) {
            throw new RuntimeException("Failed to unpack embedded DITA-OT into " + DITA_DIR, e);
        }
    }

    private static void unzip(InputStream data, File targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(data)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File out = new File(targetDir, entry.getName());
                if (entry.isDirectory()) {
                    if (!out.exists() && !out.mkdirs()) {
                        throw new IOException("Failed to create directory " + out);
                    }
                } else {
                    File parent = out.getParentFile();
                    if (!parent.exists() && !parent.mkdirs()) {
                        throw new IOException("Failed to create directory " + parent);
                    }
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
            }
        }
    }
}
