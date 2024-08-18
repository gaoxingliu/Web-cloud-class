package com.xuecheng.media;

import com.j256.simplemagic.ContentInfo;
import com.j256.simplemagic.ContentInfoUtil;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.UploadObjectArgs;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.io.*;
import java.util.concurrent.Semaphore;

/**
 * @author Mr.M
 * @version 1.0
 * @description 测试minio的sdk
 * @date 2023/2/17 11:55
 */
public class MinioTest {

    MinioClient minioClient =
            MinioClient.builder()
                    .endpoint("http://192.168.101.65:9000")
                    .credentials("minioadmin", "minioadmin")
                    .build();

    @Test
    public void test_upload() throws Exception {

        //通过扩展名得到媒体资源类型 mimeType
        //根据扩展名取出mimeType
        ContentInfo extensionMatch = ContentInfoUtil.findExtensionMatch(".mp4");
        String mimeType = MediaType.APPLICATION_OCTET_STREAM_VALUE;//通用mimeType，字节流
        if(extensionMatch!=null){
            mimeType = extensionMatch.getMimeType();
        }

        //上传文件的参数信息
        UploadObjectArgs uploadObjectArgs = UploadObjectArgs.builder()
                .bucket("testbucket")//桶
                .filename("D:\\video1.mp4") //指定本地文件路径
//                .object("1.mp4")//对象名 在桶下存储该文件
                .object("test/01/video1.mp4")//对象名 放在子目录下
                .contentType(mimeType)//设置媒体文件类型
                .build();

        //上传文件
        minioClient.uploadObject(uploadObjectArgs);



    }
    //删除文件
    @Test
    public void test_delete() throws Exception {

        //RemoveObjectArgs
        RemoveObjectArgs removeObjectArgs = RemoveObjectArgs.builder().bucket("testbucket").object("video1.mp4").build();

        //删除文件
        minioClient.removeObject(removeObjectArgs);



    }

    //查询文件 从minio中下载
    @Test
    public void test_getFile() throws Exception {

        GetObjectArgs getObjectArgs = GetObjectArgs.builder().bucket("testbucket").object("test/01/video1.mp4").build();
        //查询远程服务获取到一个流对象
        FilterInputStream inputStream = minioClient.getObject(getObjectArgs);
        //指定输出流
        FileOutputStream outputStream = new FileOutputStream(new File("D:\\video1a.mp4"));
        IOUtils.copy(inputStream,outputStream);

        /*//校验文件的完整性对文件的内容进行md5
        FileInputStream fileInputStream1 = new FileInputStream(new File("D:video1.mp4"));
        String source_md5 = DigestUtils.md5Hex(fileInputStream1);
        FileInputStream fileInputStream = new FileInputStream(new File("D:video1a.mp4"));
        String local_md5 = DigestUtils.md5Hex(fileInputStream);
        if(source_md5.equals(local_md5)){
            System.out.println("下载成功");
        }
*/
    }



   @Test
    public void test_mine() throws IOException {
       FileInputStream fileInputStream1 = new FileInputStream(new File("D:\\video.mp4"));
       String source_md5 = DigestUtils.md5Hex(fileInputStream1);
       System.out.println(source_md5);
        }


   @Test
    public void test_chunk() throws IOException {
        //1.定义原文件，分块文件以及文件传输的路径
       File SourceFile = new File("D:\\video.mp4");
       String chunkPath = "D:\\chunk\\";
       File chunkFolder = new File(chunkPath);
       if(!chunkFolder.exists()){
           chunkFolder.mkdir();
       }
       //设置分块大小，计算分块数量
       long chunkSize = 1024 * 1024 * 1;
       long chunkNum = (long)Math.ceil(SourceFile.length() * 1.0 / chunkSize);
       System.out.println("分块总数:" + chunkNum);
       //4.创建缓冲区
       byte[] b = new byte[1024];
       //使用randomaccessfile访问文件
       RandomAccessFile raf_read = new RandomAccessFile(SourceFile,"r");

       //创建分块文件，向分块文件中写数据
       for (int i = 0; i < chunkNum; i++) {
           File file = new File(chunkPath + i);
           if(file.exists()){
               file.delete();
           }
           boolean newFile = file.createNewFile();
           if(newFile){
               RandomAccessFile raf_write = new RandomAccessFile(file, "rw");
               int len = -1;
               while ((len = raf_read.read(b)) != -1) {
                   raf_write.write(b, 0, len);
                   if (file.length() >= chunkSize) {
                       break;
                   }
               }
               raf_write.close();
               System.out.println("完成分块"+i);


           }
       }
       raf_read.close();
   }

   @Test
   public void minene() throws Exception{
        Semaphore semaphore1 = new Semaphore(1); // Start with 1 permit for Thread1
       Semaphore semaphore2 = new Semaphore(0); // Start with 0 permits for Thread2
       Semaphore semaphore3 = new Semaphore(0); // Start with 0 permits for Thread3

           // Create three threads, each with their own semaphore
           Thread t1 = new NumberPrinter(semaphore1, semaphore2, 1);
           Thread t2 = new NumberPrinter(semaphore2, semaphore3, 2);
           Thread t3 = new NumberPrinter(semaphore3, semaphore1, 3);

           // Start all threads
           t1.start();
           t2.start();
           t3.start();
   }



}
class NumberPrinter extends Thread {
    private Semaphore mySemaphore;
    private Semaphore nextSemaphore;
    private int number;

    public NumberPrinter(Semaphore mySemaphore, Semaphore nextSemaphore, int number) {
        this.mySemaphore = mySemaphore;
        this.nextSemaphore = nextSemaphore;
        this.number = number;
    }

    @Override
    public void run() {
        try {
            for (int i = number; i <= 99; i += 3) { // Increment by 3 to get the right number in sequence
                mySemaphore.acquire(); // Acquire semaphore to print
                System.out.println(Thread.currentThread().getName() + ": " + i);
                nextSemaphore.release(); // Release semaphore for the next thread
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Handle interruption
        }
    }
}
