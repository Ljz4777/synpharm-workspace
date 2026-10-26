package com.synpharm.predict.pipeline;

import com.synpharm.predict.api.dto.PredictResultResponse;
import com.synpharm.predict.domain.model.AlgoType;
import com.synpharm.predict.domain.model.InputType;
import com.synpharm.predict.domain.model.OutputType;

import java.util.List;

public interface PipelineFactory {
    
    PredictResultResponse process(InputType inputType, AlgoType algoType, OutputType outputType,
                                  String inputValue, String fileUrl);
    
    PredictResultResponse process(String inputType, String algoType, String outputType,
                                  String inputValue, String fileUrl);
    
    List<PredictResultResponse> batchProcess(InputType inputType, AlgoType algoType, OutputType outputType,
                                             List<String> inputs);
    
    List<PredictResultResponse> batchProcess(String inputType, String algoType, String outputType,
                                             List<String> inputs);
    
    boolean supports(InputType inputType, AlgoType algoType, OutputType outputType);
    
    boolean supports(String inputType, String algoType, String outputType);
}