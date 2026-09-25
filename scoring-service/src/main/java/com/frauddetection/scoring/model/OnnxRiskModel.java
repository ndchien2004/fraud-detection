package com.frauddetection.scoring.model;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.frauddetection.common.Features;
import com.frauddetection.common.Transaction;
import java.nio.file.Path;
import java.util.Map;

/**
 * The model trained by model-training/train.py, run in-process with ONNX Runtime (no Python needed).
 *
 * <p>Input {@code features}: float[1, 5] in the order of train.py's FEATURES list.
 * Output {@code probabilities}: float[1, 2], column 1 = probability of fraud.
 *
 * <p>The session is created once at startup; {@link OrtSession#run} is thread-safe.
 */
public class OnnxRiskModel implements RiskModel, AutoCloseable {

    private static final String INPUT = "features";
    private static final String OUTPUT = "probabilities";

    private final OrtEnvironment env;
    private final OrtSession session;
    private final String name;

    public OnnxRiskModel(Path modelFile) {
        try {
            this.env = OrtEnvironment.getEnvironment();
            this.session = env.createSession(modelFile.toString(), sessionOptions());
            this.name = "onnx:" + modelFile.getFileName();
        } catch (OrtException e) {
            throw new IllegalStateException("Cannot load ONNX model " + modelFile, e);
        }
    }

    /**
     * One thread per inference, no spin-waiting. By default ONNX Runtime splits every call over
     * all CPU cores and keeps those threads spinning; for a model this small (well under 1 ms)
     * that brings nothing, and with dozens of concurrent requests it burned the whole CPU
     * (scoring capped at ~230 tx/s). Parallelism comes from the web server's request threads instead.
     */
    private static OrtSession.SessionOptions sessionOptions() throws OrtException {
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(1);
        options.setInterOpNumThreads(1);
        options.addConfigEntry("session.intra_op.allow_spinning", "0");
        options.addConfigEntry("session.inter_op.allow_spinning", "0");
        return options;
    }

    @Override
    public double score(Transaction tx, Features f) {
        // same order as FEATURES in model-training/train.py
        float[][] input = {{
                tx.amount(),
                f.soGiaoDich5Phut(),
                f.tongTien1Gio(),
                (float) f.lechSoVoiTrungBinh(),
                f.khoangCachBatThuong() ? 1f : 0f
        }};
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, input);
             OrtSession.Result result = session.run(Map.of(INPUT, tensor))) {
            float[][] probabilities = (float[][]) result.get(OUTPUT).orElseThrow().getValue();
            return probabilities[0][1];
        } catch (OrtException e) {
            throw new IllegalStateException("ONNX inference failed", e);
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void close() throws OrtException {
        session.close();
    }
}
