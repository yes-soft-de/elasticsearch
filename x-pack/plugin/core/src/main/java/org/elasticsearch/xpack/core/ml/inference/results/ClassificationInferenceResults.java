/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.core.ml.inference.results;

import org.elasticsearch.Version;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.ingest.IngestDocument;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.ClassificationConfig;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.InferenceConfig;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.PredictionFieldType;
import org.elasticsearch.xpack.core.ml.utils.ExceptionsHelper;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class ClassificationInferenceResults extends SingleValueInferenceResults {

    public static final String NAME = "classification";

    private final String topNumClassesField;
    private final String resultsField;
    private final String classificationLabel;
    private final List<TopClassEntry> topClasses;
    private final PredictionFieldType predictionFieldType;

    public ClassificationInferenceResults(double value,
                                          String classificationLabel,
                                          List<TopClassEntry> topClasses,
                                          InferenceConfig config) {
        this(value, classificationLabel, topClasses, Collections.emptyList(), (ClassificationConfig)config);
    }

    public ClassificationInferenceResults(double value,
                                          String classificationLabel,
                                          List<TopClassEntry> topClasses,
                                          List<FeatureImportance> featureImportance,
                                          InferenceConfig config) {
        this(value, classificationLabel, topClasses, featureImportance, (ClassificationConfig)config);
    }

    private ClassificationInferenceResults(double value,
                                           String classificationLabel,
                                           List<TopClassEntry> topClasses,
                                           List<FeatureImportance> featureImportance,
                                           ClassificationConfig classificationConfig) {
        super(value,
            SingleValueInferenceResults.takeTopFeatureImportances(featureImportance,
                classificationConfig.getNumTopFeatureImportanceValues()));
        this.classificationLabel = classificationLabel;
        this.topClasses = topClasses == null ? Collections.emptyList() : Collections.unmodifiableList(topClasses);
        this.topNumClassesField = classificationConfig.getTopClassesResultsField();
        this.resultsField = classificationConfig.getResultsField();
        this.predictionFieldType = classificationConfig.getPredictionFieldType();
    }

    public ClassificationInferenceResults(StreamInput in) throws IOException {
        super(in);
        this.classificationLabel = in.readOptionalString();
        this.topClasses = Collections.unmodifiableList(in.readList(TopClassEntry::new));
        this.topNumClassesField = in.readString();
        this.resultsField = in.readString();
        if (in.getVersion().onOrAfter(Version.V_7_8_0)) {
            this.predictionFieldType = in.readEnum(PredictionFieldType.class);
        } else {
            this.predictionFieldType = PredictionFieldType.STRING;
        }
    }

    public String getClassificationLabel() {
        return classificationLabel;
    }

    public List<TopClassEntry> getTopClasses() {
        return topClasses;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeOptionalString(classificationLabel);
        out.writeCollection(topClasses);
        out.writeString(topNumClassesField);
        out.writeString(resultsField);
        if (out.getVersion().onOrAfter(Version.V_7_8_0)) {
            out.writeEnum(predictionFieldType);
        }
    }

    @Override
    public boolean equals(Object object) {
        if (object == this) { return true; }
        if (object == null || getClass() != object.getClass()) { return false; }
        ClassificationInferenceResults that = (ClassificationInferenceResults) object;
        return Objects.equals(value(), that.value())
            && Objects.equals(classificationLabel, that.classificationLabel)
            && Objects.equals(resultsField, that.resultsField)
            && Objects.equals(topNumClassesField, that.topNumClassesField)
            && Objects.equals(topClasses, that.topClasses)
            && Objects.equals(predictionFieldType, that.predictionFieldType)
            && Objects.equals(getFeatureImportance(), that.getFeatureImportance());
    }

    @Override
    public int hashCode() {
        return Objects.hash(value(),
            classificationLabel,
            topClasses,
            resultsField,
            topNumClassesField,
            getFeatureImportance(),
            predictionFieldType);
    }

    @Override
    public String valueAsString() {
        return classificationLabel == null ? super.valueAsString() : classificationLabel;
    }

    @Override
    public void writeResult(IngestDocument document, String parentResultField) {
        ExceptionsHelper.requireNonNull(document, "document");
        ExceptionsHelper.requireNonNull(parentResultField, "parentResultField");
        document.setFieldValue(parentResultField + "." + this.resultsField,
            predictionFieldType.transformPredictedValue(value(), valueAsString()));
        if (topClasses.size() > 0) {
            document.setFieldValue(parentResultField + "." + topNumClassesField,
                topClasses.stream().map(TopClassEntry::asValueMap).collect(Collectors.toList()));
        }
        if (getFeatureImportance().size() > 0) {
            document.setFieldValue(parentResultField + "." + FEATURE_IMPORTANCE, getFeatureImportance()
                .stream()
                .map(FeatureImportance::toMap)
                .collect(Collectors.toList()));
        }
    }

    @Override
    public Map<String, Object> writeResultToMap(String parentResultField) {
        Map<String, Object> parentField = new HashMap<>();
        Map<String, Object> results = new HashMap<>();
        parentField.put(parentResultField, results);

        results.put(resultsField, valueAsString());
        if (topClasses.size() > 0) {
            results.put(topNumClassesField, topClasses.stream().map(TopClassEntry::asValueMap).collect(Collectors.toList()));
        }
        if (getFeatureImportance().size() > 0) {
            results.put(FEATURE_IMPORTANCE, getFeatureImportance());
        }

        return parentField;
    }


    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.field(resultsField, value());
        if (topClasses.size() > 0) {
            builder.field(topNumClassesField, topClasses);
        }
        if (getFeatureImportance().size() > 0) {
            builder.field(FEATURE_IMPORTANCE, getFeatureImportance());
        }
        return builder;
    }

    public static class TopClassEntry implements Writeable, ToXContentObject {

        public final ParseField CLASS_NAME = new ParseField("class_name");
        public final ParseField CLASS_PROBABILITY = new ParseField("class_probability");
        public final ParseField CLASS_SCORE = new ParseField("class_score");

        private final Object classification;
        private final double probability;
        private final double score;

        public TopClassEntry(Object classification, double probability, double score) {
            this.classification = ExceptionsHelper.requireNonNull(classification, CLASS_NAME);
            this.probability = probability;
            this.score = score;
        }

        public TopClassEntry(StreamInput in) throws IOException {
            if (in.getVersion().onOrAfter(Version.V_7_8_0)) {
                this.classification = in.readGenericValue();
            } else {
                this.classification = in.readString();
            }
            this.probability = in.readDouble();
            this.score = in.readDouble();
        }

        public Object getClassification() {
            return classification;
        }

        public double getProbability() {
            return probability;
        }

        public double getScore() {
            return score;
        }

        public Map<String, Object> asValueMap() {
            Map<String, Object> map = new HashMap<>(3, 1.0f);
            map.put(CLASS_NAME.getPreferredName(), classification);
            map.put(CLASS_PROBABILITY.getPreferredName(), probability);
            map.put(CLASS_SCORE.getPreferredName(), score);
            return map;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field(CLASS_NAME.getPreferredName(), classification);
            builder.field(CLASS_PROBABILITY.getPreferredName(), probability);
            builder.field(CLASS_SCORE.getPreferredName(), score);
            builder.endObject();
            return builder;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            if (out.getVersion().onOrAfter(Version.V_7_8_0)) {
                out.writeGenericValue(classification);
            } else {
                out.writeString(classification.toString());
            }
            out.writeDouble(probability);
            out.writeDouble(score);
        }

        @Override
        public boolean equals(Object object) {
            if (object == this) { return true; }
            if (object == null || getClass() != object.getClass()) { return false; }
            TopClassEntry that = (TopClassEntry) object;
            return Objects.equals(classification, that.classification) && probability == that.probability && score == that.score;
        }

        @Override
        public int hashCode() {
            return Objects.hash(classification, probability, score);
        }
    }
}
