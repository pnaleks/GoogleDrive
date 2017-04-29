# Simple framework to access files on Google Drive

Its main goal is to simplify and unify backup, sync, store and restore operations. It's not targeted for browsing or searching files on the drive.

This framework originates from the [Shop Assistant](https://play.google.com/store/apps/details?id=pnapp.productivity.store) project

There is an abstract class `GoogleDrive` and two implementations:

- `GoogleDriveAndroid` based on [Google Drive API for Android](https://developers.google.com/drive/android/)
- `GoogleDriveREST` using [Google Drive REST API](https://developers.google.com/drive/v3/web/about-sdk)

Main advantage of REST API is an ability of using https://www.googleapis.com/auth/drive.file scope that is not supported by Android API 

Usage examples:

```java
class MainActivity extends AppCompatActivity implements GoogleDrive.Client {
    GoogleDrive drive;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        //...
        drive = new GoogleDriveREST();
        drive.setScope(new Scope(DriveScopes.DRIVE));
        drive.init(this);
        
        GoogleDrive.setEnabled(true);
        
        test();
    }
    
    @Override
    protected void onDestroy() {
        if (drive != null) drive.destroy();
        super.onDestroy();
    }

    void test() {
        Runnable task = new Runnable() {
            @Override
            public void run() {
                InputStream is = null;
                try {
                    // Set working folder
                    String dirId = drive.cd(null, "/myDir/subDir");

                    // Get folder contents
                    List<String> list = drive.ls();

                    // Write file to disk
                    is = new FileInputStream(file);
                    String fileId = drive.write(null, "test.txt", "text/plain", is);
                    is.close();

                    // Read file back
                    is = drive.openInputStream(fileId);
                    //...

                    // Delete file
                    drive.delete(fileId);

                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    if (is != null) try {
                        is.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    drive.destroy();
                }
            }
        };
        new Thread(task).start();
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (drive != null && drive.activityResultCallback(this, requestCode, resultCode, data)) {
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void googleDriveConnected() {
        Toast.makeText(this,"Connected to Google Drive!", Toast.LENGTH_SHORT).show();
    }

}
```

The library is published on bintray and can be accessed with the next code:

```gradle
allprojects {
    repositories {
        jcenter()
        maven {
            url "http://dl.bintray.com/pnaleks/pnapp/"
        }
    }
}

dependencies {
    compile 'ru.pnapp:googledrive:1.0.2'
}
```
