---
name: inference-service-check
description: Verify inference service call logic, fix issues, and ensure proper communication between frontend and backend
---

# Inference Service Check Skill

Diagnose and fix issues with the inference service call logic, ensuring proper communication between frontend Vue components and backend Python inference services.

## When to Use

- User asks to "检查诊断模型调用逻辑是否正确，并修复"
- User asks to "检查后端模型推理服务有何问题"
- User reports issues with diagnosis model calls
- Frontend shows errors when calling inference endpoints
- Need to verify WebSocket or HTTP communication

## Workflow Steps

### Phase 1: Component Discovery
1. **Identify frontend diagnosis interface**:
   - Read `ruoyi-ui/src/views/monitor/diagnosis/index.vue`
   - Check for related components in the diagnosis directory
   - Look for API calls to inference endpoints

2. **Identify backend inference services**:
   - Glob for `ruoyi-sensor/inference/**/*.py`
   - Read `inference_service.py` and `enhanced_inference_service.py`
   - Check `db_writer.py` for database operations

3. **Check communication layers**:
   - WebSocket endpoints (`/ws/sensor`, `/ws`)
   - HTTP API endpoints (`/infer`, `/analyze`)
   - TCP data receiver (`CwruMatReceiver.java`)

### Phase 2: Call Chain Analysis
4. **Trace frontend to backend**:
   - Find API definitions in `ruoyi-ui/src/api/system/`
   - Check axios configuration in `request.js`
   - Verify timeout settings for inference calls

5. **Check model loading logic**:
   - Verify model file paths (`best_model.pth`, `best_model_classwise_maha.pth`)
   - Check `safe_torch_load` function implementations
   - Ensure proper error handling for missing models

6. **Validate data flow**:
   - Check data format expectations (numpy arrays, file uploads)
   - Verify response parsing in frontend
   - Check for mismatched data structures

### Phase 3: Common Issues Diagnosis
7. **Check for common problems**:
   - Port conflicts (5000 vs 5001)
   - Timeout issues (axios 10s default vs inference time)
   - WebSocket version compatibility
   - CORS configuration
   - Authentication token passing

8. **Verify service health**:
   - Check if Python service is running on correct port
   - Verify Java backend can reach Python service
   - Check MySQL connection for `db_writer.py`
   - Test WebSocket connectivity

### Phase 4: Fix Implementation
9. **Apply fixes based on findings**:
   - Update API endpoint URLs if changed
   - Fix timeout configurations
   - Correct data format conversions
   - Update model loading logic
   - Fix WebSocket message handlers

10. **Test fixes**:
    - Restart affected services
    - Test inference calls from frontend
    - Verify WebSocket messages are received
    - Check database writes are successful

## Key Files to Check

### Frontend
- `ruoyi-ui/src/views/monitor/diagnosis/index.vue` - Main diagnosis interface
- `ruoyi-ui/src/views/system/vibration/analysis.vue` - Vibration analysis
- `ruoyi-ui/src/api/system/bearing/` - API definitions
- `ruoyi-ui/src/utils/inference-websocket.js` - WebSocket client
- `ruoyi-ui/src/utils/request.js` - Axios configuration

### Backend
- `ruoyi-sensor/inference/inference_service.py` - Main inference service
- `ruoyi-sensor/inference/enhanced_inference_service.py` - Enhanced service
- `ruoyi-sensor/inference/db_writer.py` - Database writer
- `ruoyi-sensor/src/main/java/com/ruoyi/sensor/websocket/` - WebSocket handlers
- `get/CwruMatReceiver.java` - TCP data receiver

### Configuration
- `ruoyi-admin/src/main/resources/application.yml` - Backend config
- `.env` files in `ruoyi-ui/` - Frontend environment variables

## Common Patterns

### WebSocket Communication
```
Frontend → WebSocket → Java Backend → Python Inference → Response
```

### HTTP API Call Flow
```
Frontend → axios → Java Controller → Python Service → Response
```

### TCP Data Flow
```
Hardware → TCP Port 8888 → CwruMatReceiver → File Storage → Inference
```

## Stopping Conditions

- All relevant components identified and read
- Call chain fully traced
- Issues diagnosed and categorized
- Fixes applied and tested
- Communication verified working

## Error Handling

- If services are down: Guide user to start them
- If ports are conflicting: Help resolve port conflicts
- If authentication fails: Check token configuration
- If WebSocket fails: Check version compatibility

## Example Usage

```
User: 检查诊断模型调用逻辑是否正确，并修复
Assistant: I'll analyze the inference service call logic to identify any issues. Let me start by examining the frontend diagnosis interface and backend inference services.
```

## Related Skills

- `github-packaging`: Package after fixes
- `project-cleanup-audit`: Clean up redundant service files
