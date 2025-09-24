# Google Glass Developer Documentation

> **Note:** The Glass Explorer Edition SDK is deprecated. This documentation remains as a historical reference. For enterprise solutions, please refer to Glass Enterprise Edition.

## Table of Contents

1. [Platform Overview](#platform-overview)
2. [Getting Started](#getting-started)
3. [Design Principles](#design-principles)
4. [Development Approaches](#development-approaches)
5. [Glass Development Kit (GDK)](#glass-development-kit-gdk)
6. [Mirror API](#mirror-api)
7. [User Interface](#user-interface)
8. [Voice Input](#voice-input)
9. [Touch Gestures](#touch-gestures)
10. [Authentication](#authentication)
11. [Best Practices](#best-practices)
12. [API Reference](#api-reference)
13. [Distribution Guidelines](#distribution-guidelines)

---

## Platform Overview

Google Glass is a wearable computer with an optical head-mounted display that displays information in a smartphone-like hands-free format. Glass communicates with the Internet via natural language voice commands.

### Key Features
- **Timeline Interface**: 640 × 360 pixel cards
- **Voice Control**: "OK Glass" activation
- **Touch Pad**: Side-mounted for navigation
- **Camera**: Photo and video capture
- **Sensors**: Gyroscope, accelerometer, compass
- **Connectivity**: WiFi, Bluetooth
- **Display**: Prism projector, equivalent to 25" screen from 8 feet

### Development Options

#### Glass Development Kit (GDK)
- Runs directly on Glass
- Android-based development
- Full hardware access
- Real-time interactions
- Offline functionality

#### Mirror API
- Cloud-based REST API
- Platform independent
- Timeline integration
- Push notifications
- Simple content delivery

---

## Getting Started

### Prerequisites
- Android Studio or Eclipse with ADT
- Android SDK (API 19 - Android 4.4)
- Glass Development Kit Preview
- Glass device or emulator

### Environment Setup

1. **Install Android SDK**
```bash
# Download Android SDK
# Install API level 19 (Android 4.4 KitKat)
```

2. **Add GDK**
```
SDK Manager → Tools → Manage Add-on Sites
Add: https://developers.google.com/glass/gdk
```

3. **Create Project**
- Minimum SDK: API 19
- Target SDK: Glass Development Kit Preview
- Compile with: Glass Development Kit Preview

### Hello World Example

```java
// MainActivity.java
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        Card card = new Card(this);
        card.setText("Hello, Glass!");
        card.setFootnote("My First Glassware");
        
        setContentView(card.getView());
    }
}
```

```xml
<!-- AndroidManifest.xml -->
<activity android:name=".MainActivity">
    <intent-filter>
        <action android:name="com.google.android.glass.action.VOICE_TRIGGER" />
    </intent-filter>
    <meta-data
        android:name="com.google.android.glass.VoiceTrigger"
        android:resource="@xml/voice_trigger" />
</activity>
```

```xml
<!-- res/xml/voice_trigger.xml -->
<trigger keyword="hello glass" />
```

---

## Design Principles

### Core Principles

1. **Design for Glass**
   - Don't try to replace a smartphone
   - Build experiences unique to Glass
   - Focus on quick interactions

2. **Don't Get in the Way**
   - Be there when needed
   - Get out of the way when not
   - Avoid interrupting users

3. **Keep it Relevant**
   - Deliver timely information
   - Use context and location
   - Personalize experiences

4. **Avoid the Unexpected**
   - Be predictable
   - Don't surprise users
   - Maintain consistency

5. **Build for the Future**
   - Think beyond Glass
   - Design for multiple form factors
   - Consider accessibility

### The Four Cs

- **Current**: Information relevant to the present moment
- **Clear**: Easy to understand at a glance
- **Concise**: Read in 2-3 seconds
- **Compelling**: Worth the user's attention

### Typography Guidelines

- **Primary text**: 40 characters or less
- **Footer text**: 30 characters or less
- **Timestamp**: Relative time (e.g., "5 minutes ago")
- **Font**: Roboto Light, Regular, or Thin

---

## Glass Development Kit (GDK)

### Overview
The GDK is an add-on to the Android SDK that provides Glass-specific APIs.

### Card Builder

```java
// Simple text card
Card card = new Card(context);
card.setText("Main text");
card.setFootnote("Footer text");
card.setTimestamp("5 mins ago");

// Card with image
card.setImageLayout(Card.ImageLayout.FULL);
card.addImage(R.drawable.my_image);

// Card with menu
card.setAction(PendingIntent.getActivity(
    context, 0, menuIntent, 0));
```

### Live Cards

Live Cards run in the background and update frequently.

```java
public class LiveCardService extends Service {
    private LiveCard mLiveCard;
    private RemoteViews mRemoteViews;
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (mLiveCard == null) {
            mLiveCard = new LiveCard(this, "my_card");
            
            mRemoteViews = new RemoteViews(getPackageName(), 
                R.layout.live_card);
            mLiveCard.setViews(mRemoteViews);
            
            // Set pending intent for menu
            Intent menuIntent = new Intent(this, MenuActivity.class);
            menuIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | 
                Intent.FLAG_ACTIVITY_CLEAR_TASK);
            mLiveCard.setAction(PendingIntent.getActivity(
                this, 0, menuIntent, 0));
            
            mLiveCard.publish(PublishMode.REVEAL);
        }
        
        return START_STICKY;
    }
    
    @Override
    public void onDestroy() {
        if (mLiveCard != null && mLiveCard.isPublished()) {
            mLiveCard.unpublish();
            mLiveCard = null;
        }
        super.onDestroy();
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
```

### Immersions

Full-screen experiences with complete control.

```java
public class ImmersionActivity extends Activity {
    private GestureDetector mGestureDetector;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.immersion_layout);
        
        mGestureDetector = createGestureDetector(this);
    }
    
    private GestureDetector createGestureDetector(Context context) {
        GestureDetector gestureDetector = new GestureDetector(context);
        
        gestureDetector.setBaseListener(new GestureDetector.BaseListener() {
            @Override
            public boolean onGesture(Gesture gesture) {
                switch(gesture) {
                    case TAP:
                        handleTap();
                        return true;
                    case TWO_TAP:
                        handleTwoTap();
                        return true;
                    case SWIPE_RIGHT:
                        handleSwipeRight();
                        return true;
                    case SWIPE_LEFT:
                        handleSwipeLeft();
                        return true;
                    case SWIPE_DOWN:
                        finish();
                        return true;
                }
                return false;
            }
        });
        
        gestureDetector.setFingerListener(new GestureDetector.FingerListener() {
            @Override
            public void onFingerCountChanged(int previousCount, int currentCount) {
                // Handle finger count changes
            }
        });
        
        gestureDetector.setScrollListener(new GestureDetector.ScrollListener() {
            @Override
            public boolean onScroll(float displacement, float delta, 
                                   float velocity) {
                // Handle scroll events
                return true;
            }
        });
        
        return gestureDetector;
    }
    
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (mGestureDetector != null) {
            return mGestureDetector.onMotionEvent(event);
        }
        return super.onGenericMotionEvent(event);
    }
}
```

---

## Mirror API

### Overview
The Mirror API is a RESTful API that allows you to build web-based services for Glass.

### Timeline Items

```json
POST https://www.googleapis.com/mirror/v1/timeline

{
  "text": "Hello Glass!",
  "html": "<article><section><p>Hello Glass!</p></section></article>",
  "notification": {
    "level": "DEFAULT"
  },
  "menuItems": [
    {"action": "REPLY"},
    {"action": "READ_ALOUD"},
    {"action": "DELETE"},
    {
      "action": "CUSTOM",
      "id": "custom_action",
      "values": [{
        "displayName": "Custom Action",
        "iconUrl": "https://example.com/icon.png"
      }]
    }
  ]
}
```

### Bundling Timeline Items

```json
{
  "text": "Bundle Cover",
  "isBundleCover": true,
  "bundleId": "bundle_123"
}

{
  "text": "Bundle Item 1",
  "bundleId": "bundle_123"
}

{
  "text": "Bundle Item 2",
  "bundleId": "bundle_123"
}
```

### Attachments

```json
POST https://www.googleapis.com/upload/mirror/v1/timeline

{
  "text": "Photo attached",
  "attachments": [
    {
      "contentType": "image/jpeg",
      "contentUrl": "https://example.com/photo.jpg"
    }
  ]
}
```

### Subscriptions

```json
POST https://www.googleapis.com/mirror/v1/subscriptions

{
  "collection": "timeline",
  "userToken": "user_unique_id",
  "callbackUrl": "https://example.com/notify",
  "operation": ["UPDATE", "INSERT", "DELETE"]
}
```

### Contacts

```json
POST https://www.googleapis.com/mirror/v1/contacts

{
  "id": "my_contact",
  "displayName": "My App",
  "imageUrls": ["https://example.com/logo.png"],
  "priority": 7,
  "acceptCommands": [
    {"type": "POST_AN_UPDATE"},
    {"type": "TAKE_A_NOTE"}
  ]
}
```

### Locations

```json
GET https://www.googleapis.com/mirror/v1/locations/{id}

Response:
{
  "id": "latest",
  "timestamp": "2014-10-14T00:00:00.000Z",
  "latitude": 37.4228344,
  "longitude": -122.0850862,
  "accuracy": 10.0,
  "displayName": "Google HQ",
  "address": "1600 Amphitheatre Parkway, Mountain View, CA"
}
```

---

## User Interface

### Timeline
- Main UI paradigm
- 640 × 360 pixels
- Cards flow left and right
- Home card in center
- Past on left, future on right

### Card Templates

#### Text Card
```java
Card card = new Card(context);
card.setText("Main message");
card.setFootnote("Additional info");
```

#### Image Card
```java
Card card = new Card(context);
card.setImageLayout(ImageLayout.FULL);
card.addImage(drawable);
```

#### List Card
```java
Card card = new Card(context);
card.setText("Title");
List<String> items = Arrays.asList("Item 1", "Item 2", "Item 3");
for(String item : items) {
    card.addItem(item);
}
```

### Menu System

```java
@Override
public boolean onCreateOptionsMenu(Menu menu) {
    MenuInflater inflater = getMenuInflater();
    inflater.inflate(R.menu.main_menu, menu);
    return true;
}

@Override
public boolean onOptionsItemSelected(MenuItem item) {
    switch (item.getItemId()) {
        case R.id.action_share:
            handleShare();
            return true;
        case R.id.action_settings:
            handleSettings();
            return true;
        case R.id.action_delete:
            handleDelete();
            return true;
        default:
            return super.onOptionsItemSelected(item);
    }
}
```

```xml
<!-- res/menu/main_menu.xml -->
<menu xmlns:android="http://schemas.android.com/apk/res/android">
    <item
        android:id="@+id/action_share"
        android:title="Share"
        android:icon="@drawable/ic_share" />
    <item
        android:id="@+id/action_settings"
        android:title="Settings"
        android:icon="@drawable/ic_settings" />
    <item
        android:id="@+id/action_delete"
        android:title="Delete"
        android:icon="@drawable/ic_delete" />
</menu>
```

---

## Voice Input

### Voice Triggers

```xml
<!-- res/xml/voice_trigger.xml -->
<trigger keyword="@string/glass_voice_trigger">
    <constraints
        network="true"
        camera="true" />
    <input prompt="@string/glass_voice_prompt" />
</trigger>
```

```xml
<!-- strings.xml -->
<string name="glass_voice_trigger">start my app</string>
<string name="glass_voice_prompt">What would you like to do?</string>
```

### Voice Menu Commands

```java
@Override
public boolean onCreatePanelMenu(int featureId, Menu menu) {
    if (featureId == WindowUtils.FEATURE_VOICE_COMMANDS) {
        getMenuInflater().inflate(R.menu.voice_menu, menu);
        return true;
    }
    return super.onCreatePanelMenu(featureId, menu);
}

@Override
public boolean onMenuItemSelected(int featureId, MenuItem item) {
    if (featureId == WindowUtils.FEATURE_VOICE_COMMANDS) {
        switch (item.getItemId()) {
            case R.id.voice_menu_item:
                // Handle voice command
                return true;
        }
    }
    return super.onMenuItemSelected(featureId, item);
}
```

### Speech Recognition

```java
private static final int SPEECH_REQUEST = 0;

private void displaySpeechRecognizer() {
    Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
    startActivityForResult(intent, SPEECH_REQUEST);
}

@Override
protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    if (requestCode == SPEECH_REQUEST && resultCode == RESULT_OK) {
        List<String> results = data.getStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS);
        String spokenText = results.get(0);
        // Process the result
    }
    super.onActivityResult(requestCode, resultCode, data);
}
```

---

## Touch Gestures

### Gesture Types
- **TAP**: Select or activate
- **TWO_TAP**: Special action
- **SWIPE_FORWARD**: Next item
- **SWIPE_BACKWARD**: Previous item
- **SWIPE_DOWN**: Dismiss/Back
- **SWIPE_UP**: Show menu
- **THREE_TAP**: Debug mode

### Implementation

```java
public class TouchActivity extends Activity implements 
        GestureDetector.BaseListener,
        GestureDetector.FingerListener,
        GestureDetector.ScrollListener {
    
    private GestureDetector mGestureDetector;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mGestureDetector = new GestureDetector(this);
        mGestureDetector.setBaseListener(this);
        mGestureDetector.setFingerListener(this);
        mGestureDetector.setScrollListener(this);
    }
    
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        return mGestureDetector.onMotionEvent(event);
    }
    
    @Override
    public boolean onGesture(Gesture gesture) {
        switch(gesture) {
            case TAP:
                Log.d(TAG, "TAP");
                return true;
            case TWO_TAP:
                Log.d(TAG, "TWO_TAP");
                return true;
            case SWIPE_RIGHT:
                Log.d(TAG, "SWIPE_RIGHT");
                return true;
            case SWIPE_LEFT:
                Log.d(TAG, "SWIPE_LEFT");
                return true;
            case SWIPE_DOWN:
                Log.d(TAG, "SWIPE_DOWN");
                finish();
                return true;
        }
        return false;
    }
    
    @Override
    public void onFingerCountChanged(int previousCount, int currentCount) {
        Log.d(TAG, "Finger count: " + previousCount + " -> " + currentCount);
    }
    
    @Override
    public boolean onScroll(float displacement, float delta, float velocity) {
        Log.d(TAG, "Scroll: " + displacement);
        return true;
    }
}
```

---

## Authentication

### OAuth 2.0 Flow

1. **Authorization Request**
```
https://accounts.google.com/o/oauth2/auth?
  client_id=YOUR_CLIENT_ID&
  redirect_uri=YOUR_REDIRECT_URI&
  response_type=code&
  scope=https://www.googleapis.com/auth/glass.timeline
```

2. **Token Exchange**
```
POST https://accounts.google.com/o/oauth2/token

{
  "code": "AUTHORIZATION_CODE",
  "client_id": "YOUR_CLIENT_ID",
  "client_secret": "YOUR_CLIENT_SECRET",
  "redirect_uri": "YOUR_REDIRECT_URI",
  "grant_type": "authorization_code"
}
```

3. **API Request**
```
GET https://www.googleapis.com/mirror/v1/timeline
Authorization: Bearer ACCESS_TOKEN
```

### Scopes
- `https://www.googleapis.com/auth/glass.timeline` - Timeline access
- `https://www.googleapis.com/auth/glass.location` - Location access
- `https://www.googleapis.com/auth/userinfo.profile` - User profile

---

## Best Practices

### Performance
- Keep APK under 50MB
- Optimize for 640×360 display
- Minimize battery usage
- Use efficient data structures
- Cache data when possible
- Avoid blocking the UI thread

### User Experience
- Quick interactions (2-3 seconds)
- Clear navigation
- Consistent behavior
- Appropriate notifications
- Respect user attention
- Test while mobile

### Privacy and Security
- Transparent data collection
- Secure data transmission
- Respect recording policies
- Clear privacy policy
- User consent for permissions
- Follow platform policies

### Testing Checklist
- [ ] Test on actual hardware
- [ ] Test in bright sunlight
- [ ] Test while walking
- [ ] Test battery consumption
- [ ] Test network disconnection
- [ ] Test voice commands
- [ ] Test all gestures
- [ ] Test with prescription frames

---

## API Reference

### GDK Classes

#### Card
```java
com.google.android.glass.widget.Card
- setText(CharSequence text)
- setFootnote(CharSequence footnote)
- setTimestamp(CharSequence timestamp)
- setImageLayout(ImageLayout layout)
- addImage(int imageId)
- addImage(Drawable drawable)
- getView()
- getRemoteViews()
```

#### LiveCard
```java
com.google.android.glass.timeline.LiveCard
- setViews(RemoteViews views)
- setDirectRenderingEnabled(boolean enabled)
- getSurfaceHolder()
- setAction(PendingIntent pendingIntent)
- publish(PublishMode mode)
- unpublish()
- isPublished()
```

#### GestureDetector
```java
com.google.android.glass.touchpad.GestureDetector
- setBaseListener(BaseListener listener)
- setFingerListener(FingerListener listener)
- setScrollListener(ScrollListener listener)
- setTwoFingerScrollListener(TwoFingerScrollListener listener)
- onMotionEvent(MotionEvent event)
```

### Mirror API Endpoints

#### Timeline
- `GET /timeline` - List timeline items
- `GET /timeline/{id}` - Get timeline item
- `POST /timeline` - Insert timeline item
- `PUT /timeline/{id}` - Update timeline item
- `DELETE /timeline/{id}` - Delete timeline item
- `PATCH /timeline/{id}` - Patch timeline item

#### Contacts
- `GET /contacts` - List contacts
- `GET /contacts/{id}` - Get contact
- `POST /contacts` - Insert contact
- `PUT /contacts/{id}` - Update contact
- `DELETE /contacts/{id}` - Delete contact
- `PATCH /contacts/{id}` - Patch contact

#### Subscriptions
- `GET /subscriptions` - List subscriptions
- `POST /subscriptions` - Insert subscription
- `DELETE /subscriptions/{id}` - Delete subscription

#### Locations
- `GET /locations/{id}` - Get location
- `GET /locations` - List locations

#### Settings
- `GET /settings/{id}` - Get setting

---

## Distribution Guidelines

### Glassware Requirements

#### Functionality
- Clear value proposition
- Appropriate for Glass
- Stable and reliable
- Good performance
- Proper error handling

#### Design
- Follow Glass design principles
- Consistent UI patterns
- Appropriate notifications
- Clear navigation
- Good typography

#### Technical
- Proper permissions
- Secure data handling
- Efficient resource usage
- Proper lifecycle handling
- Clean uninstall

### Review Process

1. **Development**
   - Build and test Glassware
   - Follow guidelines
   - Prepare assets

2. **Submission**
   - Complete submission form
   - Provide APK or API details
   - Include screenshots
   - Write description

3. **Review**
   - Technical review
   - Design review
   - Policy compliance
   - User experience

4. **Approval**
   - Address feedback
   - Make required changes
   - Resubmit if needed

5. **Launch**
   - Listed in MyGlass
   - Available to users
   - Monitor feedback

### Policies

#### Prohibited Content
- Illegal activities
- Hate speech
- Violence
- Sexually explicit material
- Gambling
- Malware

#### Required Disclosures
- Data collection
- Third-party services
- In-app purchases
- Ads
- Recording capabilities

#### Branding Guidelines
- Use official Glass assets
- Don't modify logos
- Follow naming conventions
- Proper attribution
- Respect trademarks

---

## Resources

### Documentation
- [Glass Developer Site](https://developers.google.com/glass)
- [Android Developers](https://developer.android.com)
- [Mirror API Reference](https://developers.google.com/glass/v1/reference)
- [GDK Reference](https://developers.google.com/glass/develop/gdk/reference)

### Tools
- Android Studio
- Glass Development Kit
- Mirror API Playground
- MyGlass app

### Community
- Stack Overflow: `google-glass`, `google-gdk`, `google-mirror-api`
- Google+ Glass Developers Community
- Glass Explorers Community

### Sample Projects
- **Compass**: Sensor usage, Live Card
- **Stopwatch**: Timer, Live Card updates
- **Charades**: Game mechanics, Immersion
- **Timer**: Voice input, Countdown

---

## Appendix

### Common Issues

#### Battery Drain
- Minimize wake locks
- Use JobScheduler for background tasks
- Optimize network requests
- Reduce screen brightness
- Limit GPS usage

#### Voice Recognition
- Test in quiet environment
- Provide visual feedback
- Have fallback input method
- Use simple commands
- Test with accents

#### Network Issues
- Handle offline mode
- Cache important data
- Queue requests
- Provide sync status
- Graceful degradation

### Code Snippets

#### Take Photo
```java
private static final int TAKE_PICTURE_REQUEST = 1;

private void takePicture() {
    Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
    startActivityForResult(intent, TAKE_PICTURE_REQUEST);
}

@Override
protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    if (requestCode == TAKE_PICTURE_REQUEST && resultCode == RESULT_OK) {
        String picturePath = data.getStringExtra(
            Intents.EXTRA_PICTURE_FILE_PATH);
        processPictureWhenReady(picturePath);
    }
    super.onActivityResult(requestCode, resultCode, data);
}
```

#### Record Video
```java
private static final int RECORD_VIDEO_REQUEST = 2;

private void recordVideo() {
    Intent intent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
    startActivityForResult(intent, RECORD_VIDEO_REQUEST);
}

@Override
protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    if (requestCode == RECORD_VIDEO_REQUEST && resultCode == RESULT_OK) {
        String videoPath = data.getStringExtra(
            Intents.EXTRA_VIDEO_FILE_PATH);
        processVideoWhenReady(videoPath);
    }
    super.onActivityResult(requestCode, resultCode, data);
}
```

#### Sensor Access
```java
public class SensorActivity extends Activity implements SensorEventListener {
    private SensorManager mSensorManager;
    private Sensor mAccelerometer;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        mAccelerometer = mSensorManager.getDefaultSensor(
            Sensor.TYPE_ACCELEROMETER);
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        mSensorManager.registerListener(this, mAccelerometer, 
            SensorManager.SENSOR_DELAY_NORMAL);
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        mSensorManager.unregisterListener(this);
    }
    
    @Override
    public void onSensorChanged(SensorEvent event) {
        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];
        // Process accelerometer data
    }
    
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Handle accuracy changes
    }
}
```

---

*This documentation is provided for historical reference. Google Glass Explorer Edition is no longer actively supported.*
