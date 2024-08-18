package com.xuecheng.media.service.impl;

import com.j256.simplemagic.ContentInfo;
import com.j256.simplemagic.ContentInfoUtil;
import com.xuecheng.base.model.RestResponse;
import com.xuecheng.media.mapper.MediaFilesMapper;
import com.xuecheng.media.model.dto.UploadFileParamsDto;
import com.xuecheng.media.model.po.MediaFiles;
import com.xuecheng.media.service.BigFileService;
import io.minio.*;
import io.minio.messages.DeleteError;
import io.minio.messages.DeleteObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.compress.utils.IOUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
public class BigFileServiceImpl implements BigFileService {
    @Autowired
    private MediaFilesMapper mediaFilesMapper;
    @Autowired
    private MinioClient minioClient;
    @Autowired
    BigFileService currentProxy;
    //存储普通文件
    @Value("${minio.bucket.videofiles}")
    private String bucket_mediafiles;
    //存储视频
    @Value("${minio.bucket.videofiles}")
    private String bucket_videoFiles;


    @Override
    public RestResponse<Boolean> checkFile(String fileMd5) {
        //查询信息文件
        MediaFiles mediaFiles = mediaFilesMapper.selectById(fileMd5);
        if (mediaFiles != null) {
            //桶
            String bucket = mediaFiles.getBucket();
            //存储目录
            String filePath = mediaFiles.getFilePath();
            //文件流
            InputStream stream = null;
            try {
                stream = minioClient.getObject(
                        GetObjectArgs.builder()
                                .bucket(bucket)
                                .object(filePath)
                                .build());
                if (stream != null) {
                    //文件已存在
                    return RestResponse.success(true);
                }
            } catch (Exception e) {
            }
        }
        //文件不存在
        return RestResponse.success(false);


    }

    @Override
    public RestResponse<Boolean> checkchunk(String fileMd5, int chunkIndex) {
        //得到分块文件目录
        String chunkFileFolderPath = getChunkFolderPath(fileMd5);
        //得到分块文件的路径
        String chunkFilePath = chunkFileFolderPath + chunkIndex;
        //文件流
        InputStream fileInputStream = null;
        try {
            fileInputStream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucket_mediafiles)
                            .object(chunkFilePath)
                            .build()
            );
            if (fileInputStream != null) {
                return RestResponse.success(true);
            }
        } catch (Exception e) {
        }
        return RestResponse.success(false);
    }

    @Override
    public RestResponse uploadChunk(String fileMd5, int chunk, String localChunkFilePath) {
        //得到分块文件的目录路径
        String chunkFileFolderPath = getChunkFolderPath(fileMd5);
        //得到分块文件的路径
        String chunkFilePath = chunkFileFolderPath + chunk;
        //mimeType文件类型
        String mimeType = getMimeType(null);
        //将文件上传至minIO
        boolean b = addMediaFilesToMinIO(localChunkFilePath, mimeType, bucket_mediafiles, chunkFilePath);
        if (!b) {
            log.debug("上传分块文件失败:{}", chunkFilePath);
            return RestResponse.validfail(false, "上传分块失败");
        }
        log.debug("上传分块文件成功:{}", chunkFilePath);
        return RestResponse.success(true);


    }

    @Override
    public RestResponse mergeChunk(Long companyId,String fileMd5, int chunkTotal, UploadFileParamsDto uploadFileParamsDto) {
        //获取分块文件的路径
        String chunkFileFolderPath = getChunkFolderPath(fileMd5);
        //将分块文件路径组成list<composesource>
        List<ComposeSource> sourceObjectList = Stream.iterate(0, i -> ++i)
                .limit(chunkTotal)
                .map(i -> ComposeSource.builder()
                        .bucket(bucket_mediafiles)
                        .object(chunkFileFolderPath.concat(Integer.toString(i)))
                        .build())
                .collect(Collectors.toList());

        //=====合并==========
        //文件名称
        String fileName = uploadFileParamsDto.getFilename();
        //文件扩展名
        String extName = fileName.substring(fileName.lastIndexOf("."));
        //合并文件路径
        String mergeFilePath = getFilePathByMd5(fileMd5, extName);
        //合并文件
        try {
            ObjectWriteResponse response = minioClient.composeObject(
                    ComposeObjectArgs.builder()
                            .bucket(bucket_mediafiles)
                            .object(mergeFilePath)
                            .sources(sourceObjectList)
                            .build());
            log.debug("合并文件成功:{}", mergeFilePath);
        } catch (Exception e) {
            log.debug("合并文件失败,fileMd5:{},异常:{}", fileMd5, e.getMessage(), e);
            return RestResponse.validfail(false, "合并文件失败。");
        }

        //验证filemd5
        File minioFile = downloadFileFromMinIO(bucket_mediafiles, mergeFilePath);
        if (minioFile == null) {
            log.debug("下载合并后文件失败,mergeFilePath:{}", mergeFilePath);
            return RestResponse.validfail(false, "下载合并后文件失败。");
        }
        try (InputStream newFileInputStream = new FileInputStream(minioFile)) {
            //minio上文件的md5值
            String md5Hex = DigestUtils.md5Hex(newFileInputStream);
            //比较md5值，不一致则说明文件不完整
            if (!fileMd5.equals(md5Hex)) {
                return RestResponse.validfail(false, "文件合并校验失败，最终上传失败。");
            }
            //文件大小
            uploadFileParamsDto.setFileSize(minioFile.length());
        } catch (Exception e) {
            log.debug("校验文件失败,fileMd5:{},异常:{}", fileMd5, e.getMessage(), e);
            return RestResponse.validfail(false, "文件合并校验失败，最终上传失败。");
        } finally {
            if (minioFile != null) {
                minioFile.delete();
            }
        }

        //文件入库
        currentProxy.addMediaFilesToDb(companyId, fileMd5, uploadFileParamsDto, bucket_videoFiles, mergeFilePath);
        //=====清除分块文件=====
        clearChunkFiles(chunkFileFolderPath, chunkTotal);
        return RestResponse.success(true);

    }


    //得到分块文件的目录
    private String getChunkFolderPath(String fileMd5) {
        return fileMd5.substring(0, 1) + "/" + fileMd5.substring(1, 2) + "/" + fileMd5 + "/" + "chunk" + "/";
    }

    //根据扩展名获取mimeType
    private String getMimeType(String extension) {
        if (extension == null) {
            extension = "";
        }
        //根据扩展名取出mimeType
        ContentInfo extensionMatch = ContentInfoUtil.findExtensionMatch(extension);
        String mimeType = MediaType.APPLICATION_OCTET_STREAM_VALUE;//通用mimeType，字节流
        if (extensionMatch != null) {
            mimeType = extensionMatch.getMimeType();
        }
        return mimeType;

    }

    /**
     * 将文件上传到minio
     *
     * @param localFilePath 文件本地路径
     * @param mimeType      媒体类型
     * @param bucket        桶
     * @param objectName    对象名
     * @return
     */
    private boolean addMediaFilesToMinIO(String localFilePath, String mimeType, String bucket, String objectName) {
        try {
            UploadObjectArgs uploadObjectArgs = UploadObjectArgs.builder()
                    .bucket(bucket)//桶
                    .filename(localFilePath) //指定本地文件路径
                    .object(objectName)//对象名 放在子目录下
                    .contentType(mimeType)//设置媒体文件类型
                    .build();
            //上传文件
            minioClient.uploadObject(uploadObjectArgs);
            log.debug("上传文件到minio成功,bucket:{},objectName:{},错误信息:{}", bucket, objectName);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("上传文件出错,bucket:{},objectName:{},错误信息:{}", bucket, objectName, e.getMessage());
        }
        return false;
    }

    private String getFilePathByMd5(String fileMd5, String fileExt) {
        return fileMd5.substring(0, 1) + "/" + fileMd5.substring(1, 2) + "/" + fileMd5 + "/" + fileMd5 + fileExt;
    }

    /**
     * 从minio下载文件
     * @param bucket     桶
     * @param objectName 对象名称
     * @return 下载后的文件
     */
    public File downloadFileFromMinIO(String bucket, String objectName) {
        //临时文件
        File minioFile = null;
        FileOutputStream outputStream = null;
        try {
            InputStream stream = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .build());
            //创建临时文件
            minioFile = File.createTempFile("minio", ".merge");
            outputStream = new FileOutputStream(minioFile);
            IOUtils.copy(stream, outputStream);
            return minioFile;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return null;
    }

    @Transactional
    public MediaFiles addMediaFilesToDb(Long companyId, String fileMd5, UploadFileParamsDto uploadFileParamsDto, String bucket, String objectName) {
        //将文件信息保存到数据库
        MediaFiles mediaFiles = mediaFilesMapper.selectById(fileMd5);
        if (mediaFiles == null) {
            mediaFiles = new MediaFiles();
            BeanUtils.copyProperties(uploadFileParamsDto, mediaFiles);
            //文件id
            mediaFiles.setId(fileMd5);
            //机构id
            mediaFiles.setCompanyId(companyId);
            //桶
            mediaFiles.setBucket(bucket);
            //file_path
            mediaFiles.setFilePath(objectName);
            //file_id
            mediaFiles.setFileId(fileMd5);
            //url
            mediaFiles.setUrl("/" + bucket + "/" + objectName);
            //上传时间
            mediaFiles.setCreateDate(LocalDateTime.now());
            //状态
            mediaFiles.setStatus("1");
            //审核状态
            mediaFiles.setAuditStatus("002003");
            //插入数据库
            int insert = mediaFilesMapper.insert(mediaFiles);
            if (insert <= 0) {
                log.debug("向数据库保存文件失败,bucket:{},objectName:{}", bucket, objectName);
                return null;
            }
            return mediaFiles;

        }
        return mediaFiles;

    }

    /**
     * 清除分块文件
     *
     * @param chunkFileFolderPath 分块文件路径
     * @param chunkTotal          分块文件总数
     */
    private void clearChunkFiles(String chunkFileFolderPath, int chunkTotal) {

        try {
            List<DeleteObject> deleteObjects = Stream.iterate(0, i -> ++i)
                    .limit(chunkTotal)
                    .map(i -> new DeleteObject(chunkFileFolderPath.concat(Integer.toString(i))))
                    .collect(Collectors.toList());

            RemoveObjectsArgs removeObjectsArgs = RemoveObjectsArgs.builder().bucket("video").objects(deleteObjects).build();
            Iterable<Result<DeleteError>> results = minioClient.removeObjects(removeObjectsArgs);
            results.forEach(r -> {
                DeleteError deleteError = null;
                try {
                    deleteError = r.get();
                } catch (Exception e) {
                    e.printStackTrace();
                    log.error("清楚分块文件失败,objectname:{}", deleteError.objectName(), e);
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            log.error("清楚分块文件失败,chunkFileFolderPath:{}", chunkFileFolderPath, e);
        }

    }
}