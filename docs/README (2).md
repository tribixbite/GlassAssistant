# Google Glass Developer Documentation - Offline Archive

This is an offline archive of the Google Glass Explorer Edition developer documentation. Since the Glass Explorer Edition SDK has been deprecated, this documentation serves as a historical reference.

## Contents

This archive contains the complete Google Glass developer documentation in multiple formats:

### 📄 Files Included

1. **google-glass-docs.html** - Complete offline-browsable documentation
   - Interactive navigation
   - Search functionality
   - Styled for easy reading
   - No external dependencies

2. **google-glass-docs.md** - Comprehensive Markdown documentation
   - Perfect for LLM context use
   - Easy to read in any text editor
   - Complete API references and code examples
   - Well-structured with table of contents

3. **google-glass-docs.json** - Structured JSON format
   - Programmatic access to documentation
   - Hierarchical organization
   - Easy to parse and process

4. **README.md** - This file

## Usage

### For Web Browsing
Simply open `google-glass-docs.html` in any modern web browser. The documentation is fully self-contained with:
- Sidebar navigation
- Search functionality
- Code syntax highlighting
- Responsive design

### For LLM Context
Use `google-glass-docs.md` as context when working with Large Language Models:
- Copy relevant sections as needed
- Full API documentation included
- Code examples in proper markdown format
- Comprehensive coverage of all Glass development topics

### For Programmatic Access
Parse `google-glass-docs.json` in your applications:
```javascript
const glasssDocs = require('./google-glass-docs.json');
console.log(glassDocs.gdk.components.card_builder);
```

## Documentation Coverage

### Development Platforms
- **Glass Development Kit (GDK)** - Native Android development
- **Mirror API** - Cloud-based REST API

### Topics Included
- Platform Overview
- Getting Started Guide
- Design Principles
- User Interface Guidelines
- Voice Input Implementation
- Touch Gestures
- Authentication (OAuth 2.0)
- Live Cards
- Immersions
- Timeline Management
- API References
- Best Practices
- Distribution Guidelines
- Code Samples
- Common Issues & Solutions

## Key Features Documented

### GDK Development
- Card Builder API
- Live Card Services
- Immersion Activities
- Gesture Detection
- Voice Triggers
- Sensor Access
- Camera Integration

### Mirror API
- Timeline Items
- Subscriptions
- Contacts
- Locations
- Menu Items
- Attachments
- Bundling

## Code Examples

The documentation includes complete code examples for:
- Hello World application
- Voice command integration
- Gesture handling
- Live Card implementation
- Timeline management
- OAuth authentication
- Sensor data access
- Camera/video capture

## Important Notes

⚠️ **Deprecation Notice**: The Glass Explorer Edition SDK is deprecated. This documentation is provided for:
- Historical reference
- Legacy application maintenance
- Educational purposes
- Understanding wearable computing concepts

For current Glass development, refer to Glass Enterprise Edition documentation.

## Quick Start Example

```java
// Basic Glass Card
Card card = new Card(context);
card.setText("Hello Glass!");
card.setFootnote("My First Glassware");
setContentView(card.getView());
```

## Resources

While the Explorer Edition is deprecated, these concepts remain relevant for:
- Wearable computing development
- AR/VR interface design
- Voice-first interfaces
- Hands-free computing
- Context-aware applications

## License

This documentation archive is provided as-is for educational and reference purposes. Original content © Google Inc.

---

*Generated from https://developers.google.com/glass/ for offline/archival use.*
