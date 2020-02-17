package systems.dmx.dita;

import static systems.dmx.dita.Constants.*;

import systems.dmx.core.Topic;
import systems.dmx.core.service.CoreService;
import systems.dmx.topicmaps.TopicmapsService;

import org.dita.dost.Processor;
import org.dita.dost.ProcessorFactory;
import org.dita.dost.util.Configuration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;



class DITAProcess {

    // ------------------------------------------------------------------------------------------------------- Constants

    // TODO: use system properties
    private static final File DITA_DIR   = new File("/usr/local/Cellar/dita-ot/3.4/libexec");
    private static final File OUTPUT_DIR = new File("/Users/jri/Documents/Test/dita-ot");
    private static final File TEMP_DIR   = new File("/Users/jri/Documents/Test/dita-ot/tmp");

    private static final DITAExporter exporter = new DITAExporter(TEMP_DIR);
    private static final ProcessorFactory pf = ProcessorFactory.newInstance(DITA_DIR);

    static {
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

    // ------------------------------------------------------------------------------------------------- Private Methods

    private void runProcessor() {
        ClassLoader currentClassLoader = null;
        try {
            currentClassLoader = Thread.currentThread().getContextClassLoader();
            ClassLoader bundleClassloader = getClass().getClassLoader();
            Thread.currentThread().setContextClassLoader(bundleClassloader);
            logDebugInfo(currentClassLoader, bundleClassloader);
            //
            pf.newProcessor("html5")
                //.setInput(new File("/usr/local/Cellar/dita-ot/3.4/libexec/docsrc/samples/sequence.ditamap"))
                .setInput(new File(TEMP_DIR, topicmapId + ".xml"))
                .setOutputDir(OUTPUT_DIR);
                //.setProperty("nav-toc", "partial")
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
            Topic topic = tmNav.getRelatedTopic(processorId, PROCESSOR_START, ROLE_TYPE_PROCESSOR, ROLE_TYPE_START,
                null);
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
            return tmNav.getRelatedTopic(topicId, SEQUENCE, ROLE_TYPE_PREDECESSOR, ROLE_TYPE_SUCCESSOR, null);
        } catch (Exception e) {
            throw new RuntimeException("Finding next topic in sequence failed", e);
        }
    }

    private void logDebugInfo(ClassLoader currentClassLoader, ClassLoader bundleClassloader) {
        logger.info("org.osgi.framework.bootdelegation=" + System.getProperty("org.osgi.framework.bootdelegation") +
            "\n      org.osgi.framework.system.packages.extra=" +
            System.getProperty("org.osgi.framework.system.packages.extra") +
            "\n      Current ClassLoader=" + currentClassLoader + ", parent=" + currentClassLoader.getParent() +
            "\n      Bundle ClassLoader=" + bundleClassloader + ", parent=" + bundleClassloader.getParent() +
            "\n      Available transtypes=" + Configuration.transtypes);
    }
}
