package com.synpharm.predict.pipeline;

import com.synpharm.predict.api.dto.ParsedInput;
import com.synpharm.predict.api.dto.AlgoResponse;
import com.synpharm.predict.domain.model.AlgoType;

import java.util.List;

public interface AlgoExecutor {
    AlgoType getAlgoType();
    AlgoResponse execute(ParsedInput inputData);
    List<AlgoResponse> batchExecute(List<ParsedInput> inputDataList);
}