package com.qiniu.android.storage;

import com.qiniu.android.http.ResponseInfo;
import com.qiniu.android.http.request.RequestTranscation;
import com.qiniu.android.http.request.UploadFileInfo;
import com.qiniu.android.http.request.UploadRegion;
import com.qiniu.android.http.request.handler.RequestProgressHandler;
import com.qiniu.android.http.metrics.UploadRegionRequestMetrics;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;

public class ResumeUpload extends PartsUpload {

    private double previousPercent;
    private RequestTranscation uploadTranscation;

    private ResponseInfo uploadChunkErrorResponseInfo;
    private JSONObject uploadChunkErrorResponse;

    public ResumeUpload(File file,
                        String key,
                        UpToken token,
                        UploadOptions option,
                        Configuration config,
                        Recorder recorder,
                        String recorderKey,
                        UpTaskCompletionHandler completionHandler) {
        super(file, key, token, option, config, recorder, recorderKey, completionHandler);
    }

    @Override
    public void startToUpload() {
        previousPercent = 0;
        uploadChunkErrorResponseInfo = null;
        uploadChunkErrorResponse = null;

        uploadRestChunk(new UploadChunkCompleteHandler() {
            @Override
            public void complete() {

                UploadFileInfo uploadFileInfo = getUploadFileInfo();
                if (!uploadFileInfo.isAllUploaded() || uploadChunkErrorResponseInfo != null){
                    if (uploadChunkErrorResponseInfo.couldRetry() && config.allowBackupHost) {
                        boolean isSwitched = switchRegionAndUpload();
                        if (!isSwitched){
                            completeAction(uploadChunkErrorResponseInfo, uploadChunkErrorResponse);
                        }
                    } else {
                        completeAction(uploadChunkErrorResponseInfo, uploadChunkErrorResponse);
                    }
                } else {
                    makeFile(new UploadFileCompleteHandler() {
                        @Override
                        public void complete(ResponseInfo responseInfo, JSONObject response) {
                            if (responseInfo == null || !responseInfo.isOK()){
                                boolean isSwitched = switchRegionAndUpload();
                                if (!isSwitched){
                                    completeAction(responseInfo, response);
                                }
                            } else {
                                option.progressHandler.progress(key, 1.0);
                                removeUploadInfoRecord();
                                completeAction(responseInfo, response);
                            }
                        }
                    });
                }
            }
        });
    }

    private void uploadRestChunk(UploadChunkCompleteHandler completeHandler){
        final UploadFileInfo uploadFileInfo = getUploadFileInfo();
        if (uploadFileInfo == null){
            if (uploadChunkErrorResponseInfo == null){
                uploadChunkErrorResponseInfo = ResponseInfo.invalidArgument("regions error");
                uploadChunkErrorResponse = uploadChunkErrorResponseInfo.response;
            }
            completeHandler.complete();
            return;
        }

        UploadRegion currentRegion = getCurrentRegion();
        if (currentRegion == null){
            if (uploadChunkErrorResponseInfo == null){
                uploadChunkErrorResponseInfo = ResponseInfo.invalidArgument("server error");
                uploadChunkErrorResponse = uploadChunkErrorResponseInfo.response;
            }
            completeHandler.complete();
            return;
        }

        final UploadFileInfo.UploadData chunk = uploadFileInfo.nextUploadData();
        UploadFileInfo.UploadBlock block = chunk != null ? uploadFileInfo.blockWithIndex(chunk.blockIndex) : null;

        RequestProgressHandler progressHandler = new RequestProgressHandler() {
            @Override
            public void progress(long totalBytesWritten, long totalBytesExpectedToWrite) {
                chunk.progress = (double)totalBytesWritten / (double)totalBytesExpectedToWrite;
                double percent = uploadFileInfo.progress();
                if (percent > 0.95){
                    percent = 0.95;
                }
                if (percent > previousPercent){
                    previousPercent = percent;
                } else {
                    percent = previousPercent;
                }
                option.progressHandler.progress(key, percent);
            }
        };
        if (chunk == null){
            completeHandler.complete();
        } else if (chunk.isFirstData()){
            makeBlock(block, chunk, progressHandler, completeHandler);
        } else {
            uploadChunk(block, chunk, progressHandler, completeHandler);
        }
    }

    private void makeBlock(final UploadFileInfo.UploadBlock block,
                           final UploadFileInfo.UploadData chunk,
                           final RequestProgressHandler progressHandler,
                           final UploadChunkCompleteHandler completeHandler){

        chunk.isUploading = true;
        chunk.isCompleted = false;

        byte[] chunkData = getDataWithChunk(chunk, block);
        if (chunkData == null){
            ResponseInfo responseInfo = ResponseInfo.invalidArgument("upload chunk was null");
            completeAction(responseInfo, responseInfo.response);

            chunk.isUploading = false;
            chunk.isCompleted = false;
            return;
        }

        RequestTranscation transcation = createUploadRequestTranscation();
        transcation.makeBlock(block.offset, block.size, chunkData, true, progressHandler, new RequestTranscation.RequestCompleteHandler() {
            @Override
            public void complete(ResponseInfo responseInfo, UploadRegionRequestMetrics requestMetrics, JSONObject response) {
                String blockContext = null;
                if (response != null){
                    try {
                        blockContext = response.getString("ctx");
                    } catch (JSONException e) {}
                }
                if (responseInfo.isOK() && blockContext != null){
                    block.context = blockContext;
                    chunk.isUploading = false;
                    chunk.isCompleted = true;
                    recordUploadInfo();
                    uploadRestChunk(completeHandler);
                } else {
                    chunk.isUploading = false;
                    chunk.isCompleted = false;
                    uploadChunkErrorResponse = response;
                    uploadChunkErrorResponseInfo = responseInfo;
                    completeHandler.complete();
                }
            }
        });
    }

    private void uploadChunk(final UploadFileInfo.UploadBlock block,
                             final UploadFileInfo.UploadData chunk,
                             final RequestProgressHandler progressHandler,
                             final UploadChunkCompleteHandler completeHandler){

        chunk.isUploading = true;
        chunk.isCompleted = false;

        byte[] chunkData = getDataWithChunk(chunk, block);
        if (chunkData == null){
            ResponseInfo responseInfo = ResponseInfo.invalidArgument("upload chunk was null");
            completeAction(responseInfo, responseInfo.response);

            chunk.isUploading = false;
            chunk.isCompleted = false;
            return;
        }

        RequestTranscation transcation = createUploadRequestTranscation();
        transcation.uploadChunk(block.context, block.offset, chunkData, chunk.offset, true, progressHandler, new RequestTranscation.RequestCompleteHandler() {
            @Override
            public void complete(ResponseInfo responseInfo, UploadRegionRequestMetrics requestMetrics, JSONObject response) {
                String blockContext = null;
                if (response != null){
                    try {
                        blockContext = response.getString("ctx");
                    } catch (JSONException e) {}
                }
                if (responseInfo.isOK() && blockContext != null){
                    block.context = blockContext;
                    chunk.isUploading = false;
                    chunk.isCompleted = true;
                    recordUploadInfo();
                    uploadRestChunk(completeHandler);
                } else {
                    chunk.isUploading = false;
                    chunk.isCompleted = false;
                    uploadChunkErrorResponse = response;
                    uploadChunkErrorResponseInfo = responseInfo;
                    completeHandler.complete();
                }
            }
        });

    }

    private void makeFile(final UploadFileCompleteHandler completeHandler){
        UploadFileInfo uploadFileInfo = getUploadFileInfo();

        RequestTranscation transcation = createUploadRequestTranscation();
        ArrayList<String> contextsList = uploadFileInfo.allBlocksContexts();
        String[] contexts = contextsList.toArray(new String[contextsList.size()]);
        transcation.makeFile(uploadFileInfo.size, fileName,  contexts, true, new RequestTranscation.RequestCompleteHandler(){

            @Override
            public void complete(ResponseInfo responseInfo, UploadRegionRequestMetrics requestMetrics, JSONObject response) {

                setCurrentRegionRequestMetrics(requestMetrics);
                completeHandler.complete(responseInfo, response);
            }
        });
    }

    private RequestTranscation createUploadRequestTranscation(){
        RequestTranscation transcation = new RequestTranscation(config, option, getTargetRegion(), getCurrentRegion(), key, token);
        uploadTranscation = transcation;
        return transcation;
    }

    private byte[] getDataWithChunk(UploadFileInfo.UploadData chunk,
                                    UploadFileInfo.UploadBlock block){
        RandomAccessFile randomAccessFile = getRandomAccessFile();
        if (randomAccessFile == null || chunk == null || block == null){
            return null;
        }
        byte[] data = new byte[(int)chunk.size];
        try {
            randomAccessFile.seek((chunk.offset + block.offset));
            randomAccessFile.read(data, 0, (int)chunk.size);
        } catch (IOException e) {
            data = null;
        }
        return data;
    }

    private interface UploadChunkCompleteHandler{
        void complete();
    }

    private interface UploadFileCompleteHandler{
        void complete(ResponseInfo responseInfo, JSONObject response);
    }
}
