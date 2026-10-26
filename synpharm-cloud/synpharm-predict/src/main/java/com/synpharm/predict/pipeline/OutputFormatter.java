package com.synpharm.predict.pipeline;

import com.synpharm.predict.api.dto.AlgoResponse;
import com.synpharm.predict.api.dto.PredictResultResponse;
import com.synpharm.predict.domain.model.OutputType;

import java.util.List;

public interface OutputFormatter {
    OutputType getOutputType();
    PredictResultResponse format(AlgoResponse resultData);
    List<PredictResultResponse> batchFormat(List<AlgoResponse> resultDataList);
}