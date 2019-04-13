package io.lumify.gpw.video;

import com.google.inject.Inject;
import io.lumify.core.ingest.graphProperty.GraphPropertyWorkData;
import io.lumify.core.ingest.graphProperty.GraphPropertyWorker;
import io.lumify.core.ingest.graphProperty.GraphPropertyWorkerPrepareData;
import io.lumify.core.model.properties.LumifyProperties;
import io.lumify.core.model.properties.MediaLumifyProperties;
import io.lumify.core.model.properties.types.DoubleLumifyProperty;
import io.lumify.core.model.properties.types.IntegerLumifyProperty;
import io.lumify.core.util.LumifyLogger;
import io.lumify.core.util.LumifyLoggerFactory;
import io.lumify.core.util.ProcessRunner;
import io.lumify.gpw.util.FFprobeRotationUtil;
import org.securegraph.Element;
import org.securegraph.Metadata;
import org.securegraph.Property;
import org.securegraph.Vertex;
import org.securegraph.mutation.ExistingElementMutation;
import org.securegraph.property.StreamingPropertyValue;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;

public class VideoPosterFrameWorker extends GraphPropertyWorker {
    private static final LumifyLogger LOGGER = LumifyLoggerFactory.getLogger(VideoPosterFrameWorker.class);
    private static final String PROPERTY_KEY = VideoPosterFrameWorker.class.getName();
    private ProcessRunner processRunner;
    private DoubleLumifyProperty durationProperty;
    private IntegerLumifyProperty videoRotationProperty;

    @Override
    public void prepare(GraphPropertyWorkerPrepareData workerPrepareData) throws Exception {
        super.prepare(workerPrepareData);
        durationProperty = new DoubleLumifyProperty(getOntologyRepository().getRequiredPropertyIRIByIntent("media.duration"));
        videoRotationProperty = new IntegerLumifyProperty(getOntologyRepository().getRequiredPropertyIRIByIntent("media.clockwiseRotation"));
    }

    @Override
    public void execute(InputStream in, GraphPropertyWorkData data) throws Exception {
        File videoPosterFrameFile = File.createTempFile("video_poster_frame", ".png");
        String[] ffmpegOptionsArray = prepareFFMPEGOptions(data, videoPosterFrameFile);
        try {
            processRunner.execute(
                    "ffmpeg",
                    ffmpegOptionsArray,
                    null,
                    data.getLocalFile().getAbsolutePath() + ": "
            );

            if (videoPosterFrameFile.length() == 0) {
                throw new RuntimeException("Poster frame not created. Zero length file detected. (from: " + data.getLocalFile().getAbsolutePath() + ")");
            }

            try (InputStream videoPosterFrameFileIn = new FileInputStream(videoPosterFrameFile)) {
                ExistingElementMutation<Vertex> m = data.getElement().prepareMutation();

                StreamingPropertyValue spv = new StreamingPropertyValue(videoPosterFrameFileIn, byte[].class);
                spv.searchIndex(false);
                Metadata metadata = new Metadata();
                metadata.add(LumifyProperties.MIME_TYPE.getPropertyName(), "image/png", getVisibilityTranslator().getDefaultVisibility());
                MediaLumifyProperties.RAW_POSTER_FRAME.addPropertyValue(m, PROPERTY_KEY, spv, metadata, data.getProperty().getVisibility());
                m.save(getAuthorizations());
                getGraph().flush();
            }
        } finally {
            if (!videoPosterFrameFile.delete()) {
                LOGGER.warn("Could not delete %s", videoPosterFrameFile.getAbsolutePath());
            }
        }
    }

    private String[] prepareFFMPEGOptions(GraphPropertyWorkData data, File videoPosterFrameFile) {
        ArrayList<String> ffmpegOptionsList = new ArrayList<>();
        Double duration = durationProperty.getPropertyValue(data.getElement(), 0);

        if (duration != null) {
            ffmpegOptionsList.add("-itsoffset");
            ffmpegOptionsList.add("-" + (duration / 3.0));
        }

        ffmpegOptionsList.add("-i");
        ffmpegOptionsList.add(data.getLocalFile().getAbsolutePath());
        ffmpegOptionsList.add("-vcodec");
        ffmpegOptionsList.add("png");
        ffmpegOptionsList.add("-vframes");
        ffmpegOptionsList.add("1");
        ffmpegOptionsList.add("-an");
        ffmpegOptionsList.add("-f");
        ffmpegOptionsList.add("rawvideo");

        Integer videoRotation = videoRotationProperty.getPropertyValue(data.getElement());
        if (videoRotation != null) {
            //Scale.
            //Will not force conversion to 720:480 aspect ratio, but will resize video with original aspect ratio.
            if (videoRotation == 0 || videoRotation == 180) {
                ffmpegOptionsList.add("-s");
                ffmpegOptionsList.add("720x480");
            } else if (videoRotation == 90 || videoRotation == 270) {
                ffmpegOptionsList.add("-s");
                ffmpegOptionsList.add("480x720");
            }

            String[] ffmpegRotationOptions = FFprobeRotationUtil.createFFMPEGRotationOptions(videoRotation);
            //Rotate
            if (ffmpegRotationOptions != null) {
                ffmpegOptionsList.add(ffmpegRotationOptions[0]);
                ffmpegOptionsList.add(ffmpegRotationOptions[1]);
            }
        }

        ffmpegOptionsList.add("-y");
        ffmpegOptionsList.add(videoPosterFrameFile.getAbsolutePath());

        return ffmpegOptionsList.toArray(new String[ffmpegOptionsList.size()]);
    }

    @Override
    public boolean isHandled(Element element, Property property) {
        if (property == null) {
            return false;
        }

        if (!property.getName().equals(LumifyProperties.RAW.getPropertyName())) {
            return false;
        }
        String mimeType = LumifyProperties.MIME_TYPE.getMetadataValue(property.getMetadata(), null);
        if (mimeType == null || !mimeType.startsWith("video")) {
            return false;
        }

        return true;
    }

    @Override
    public boolean isLocalFileRequired() {
        return true;
    }

    @Inject
    public void setProcessRunner(ProcessRunner ffmpeg) {
        this.processRunner = ffmpeg;
    }
}
