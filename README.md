# woosim_printer_flutter

# NOTES:
- I create this repo for those who wants to use woosim printer with their mobile app.. the sample app provided by woosim is using fully Java while mine use dart as its base and both Java and Kotlin for its background services..
- Both Java and Kotlin background services are ported from the original sample app provided by woosim from their official website
- IF got error about AGP version is lower than 8.2.1, then go to android/settings.gradle and set the plugin com.android.application to 8.7.1
- MUST add the below codes into android/app/build.gradle in order to be able to compile/run the app

// android/app/build.gradle:

    // other existing codes here...
    
    android {
        // other existing codes here...
    
        repositories {
            flatDir {
                dirs 'libs'
            }
        }
    }
    
    dependencies {
        //other existing codes here...

        // woosim sdk
        implementation files('libs/WoosimLib262.jar')
    }
    
    // other existing codes here...
