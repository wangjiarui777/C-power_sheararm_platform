package com.ruoyi.web.controller.system;

import java.util.List;
import java.util.stream.Collectors;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.sensor.domain.entity.PhmDeviceEntity;
import com.ruoyi.sensor.service.PhmService;
import com.ruoyi.system.domain.SysUserDeviceAuth;
import com.ruoyi.system.service.ISysRoleService;
import com.ruoyi.system.service.ISysUserDeviceService;
import com.ruoyi.system.service.ISysUserService;
import com.ruoyi.system.security.PasswordPolicyService;
import com.ruoyi.framework.web.service.TokenService;

/**
 * 鐢ㄦ埛淇℃伅
 * 
 * @author ruoyi
 */
@RestController
@RequestMapping("/system/user")
public class SysUserController extends BaseController
{
    @Autowired
    private ISysUserService userService;

    @Autowired
    private ISysRoleService roleService;

    @Autowired
    private ISysUserDeviceService userDeviceService;

    @Autowired
    private PhmService phmService;

    @Autowired
    private PasswordPolicyService passwordPolicyService;

    @Autowired
    private TokenService tokenService;

    /**
     * 鑾峰彇鐢ㄦ埛鍒楄〃
     */
    @PreAuthorize("@ss.hasPermi('system:user:list')")
    @GetMapping("/list")
    public TableDataInfo list(SysUser user)
    {
        startPage();
        List<SysUser> list = userService.selectUserList(user);
        return getDataTable(list);
    }

    @Log(title = "鐢ㄦ埛绠＄悊", businessType = BusinessType.EXPORT)
    @PreAuthorize("@ss.hasPermi('system:user:export')")
    @PostMapping("/export")
    public void export(HttpServletResponse response, SysUser user)
    {
        List<SysUser> list = userService.selectUserList(user);
        ExcelUtil<SysUser> util = new ExcelUtil<SysUser>(SysUser.class);
        util.exportCsv(response, list, "用户数据");
    }

    @Log(title = "鐢ㄦ埛绠＄悊", businessType = BusinessType.IMPORT)
    @PreAuthorize("@ss.hasPermi('system:user:import')")
    @PostMapping("/importData")
    public AjaxResult importData(MultipartFile file, boolean updateSupport) throws Exception
    {
        ExcelUtil<SysUser> util = new ExcelUtil<SysUser>(SysUser.class);
        List<SysUser> userList = util.importExcel(file.getInputStream());
        String operName = getUsername();
        String message = userService.importUser(userList, updateSupport, operName);
        return success(message);
    }

    @PostMapping("/importTemplate")
    public void importTemplate(HttpServletResponse response)
    {
        ExcelUtil<SysUser> util = new ExcelUtil<SysUser>(SysUser.class);
        util.importTemplateExcel(response, "鐢ㄦ埛鏁版嵁");
    }

    /**
     * 鏍规嵁鐢ㄦ埛缂栧彿鑾峰彇璇︾粏淇℃伅
     */
    @PreAuthorize("@ss.hasPermi('system:user:query')")
    @GetMapping(value = { "/", "/{userId}" })
    public AjaxResult getInfo(@PathVariable(value = "userId", required = false) Long userId)
    {
        AjaxResult ajax = AjaxResult.success();
        if (StringUtils.isNotNull(userId))
        {
            userService.checkUserDataScope(userId);
            SysUser sysUser = userService.selectUserById(userId);
            ajax.put(AjaxResult.DATA_TAG, sysUser);
            ajax.put("roleIds", sysUser.getRoles().stream().map(SysRole::getRoleId).collect(Collectors.toList()));
        }
        List<SysRole> roles = roleService.selectRoleAll();
        ajax.put("roles", SecurityUtils.isAdmin(userId) ? roles : roles.stream().filter(r -> !r.isAdmin()).collect(Collectors.toList()));
        return ajax;
    }

    /**
     * 鏂板鐢ㄦ埛
     */
    @PreAuthorize("@ss.hasPermi('system:user:add')")
    @Log(title = "鐢ㄦ埛绠＄悊", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@Validated @RequestBody SysUser user)
    {
        if (!userService.checkUserNameUnique(user))
        {
            return error("鏂板鐢ㄦ埛'" + user.getUserName() + "'澶辫触锛岀櫥褰曡处鍙峰凡瀛樺湪");
        }
        else if (StringUtils.isNotEmpty(user.getPhonenumber()) && !userService.checkPhoneUnique(user))
        {
            return error("鏂板鐢ㄦ埛'" + user.getUserName() + "'澶辫触锛屾墜鏈哄彿鐮佸凡瀛樺湪");
        }
        else if (StringUtils.isNotEmpty(user.getEmail()) && !userService.checkEmailUnique(user))
        {
            return error("鏂板鐢ㄦ埛'" + user.getUserName() + "'澶辫触锛岄偖绠辫处鍙峰凡瀛樺湪");
        }
        user.setCreateBy(getUsername());
        passwordPolicyService.validate(user.getPassword(), user);
        user.setMustChangePassword(true);
        user.setPassword(SecurityUtils.encryptPassword(user.getPassword()));
        return toAjax(userService.insertUser(user));
    }

    /**
     * 淇敼鐢ㄦ埛
     */
    @PreAuthorize("@ss.hasPermi('system:user:edit')")
    @Log(title = "鐢ㄦ埛绠＄悊", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@Validated @RequestBody SysUser user)
    {
        userService.checkUserAllowed(user);
        userService.checkUserDataScope(user.getUserId());
        if (!userService.checkUserNameUnique(user))
        {
            return error("淇敼鐢ㄦ埛'" + user.getUserName() + "'澶辫触锛岀櫥褰曡处鍙峰凡瀛樺湪");
        }
        else if (StringUtils.isNotEmpty(user.getPhonenumber()) && !userService.checkPhoneUnique(user))
        {
            return error("淇敼鐢ㄦ埛'" + user.getUserName() + "'澶辫触锛屾墜鏈哄彿鐮佸凡瀛樺湪");
        }
        else if (StringUtils.isNotEmpty(user.getEmail()) && !userService.checkEmailUnique(user))
        {
            return error("淇敼鐢ㄦ埛'" + user.getUserName() + "'澶辫触锛岄偖绠辫处鍙峰凡瀛樺湪");
        }
        user.setUpdateBy(getUsername());
        int rows = userService.updateUser(user);
        if (rows > 0) tokenService.revokeUserSessions(user.getUserId());
        return toAjax(rows);
    }

    /**
     * 鍒犻櫎鐢ㄦ埛
     */
    @PreAuthorize("@ss.hasPermi('system:user:remove')")
    @Log(title = "鐢ㄦ埛绠＄悊", businessType = BusinessType.DELETE)
    @DeleteMapping("/{userIds}")
    public AjaxResult remove(@PathVariable Long[] userIds)
    {
        if (ArrayUtils.contains(userIds, getUserId()))
        {
            return error("褰撳墠鐢ㄦ埛涓嶈兘鍒犻櫎");
        }
        return toAjax(userService.deleteUserByIds(userIds));
    }

    /**
     * 閲嶇疆瀵嗙爜
     */
    @PreAuthorize("@ss.hasPermi('system:user:resetPwd')")
    @Log(title = "鐢ㄦ埛绠＄悊", businessType = BusinessType.UPDATE)
    @PutMapping("/resetPwd")
    public AjaxResult resetPwd(@RequestBody SysUser user)
    {
        userService.checkUserAllowed(user);
        userService.checkUserDataScope(user.getUserId());
        SysUser target = userService.selectUserById(user.getUserId());
        passwordPolicyService.validate(user.getPassword(), target);
        user.setPassword(SecurityUtils.encryptPassword(user.getPassword()));
        user.setUpdateBy(getUsername());
        int rows = userService.resetPwd(user);
        if (rows > 0) tokenService.revokeUserSessions(user.getUserId());
        return toAjax(rows);
    }

    /**
     * 鐘舵€佷慨鏀?
     */
    @PreAuthorize("@ss.hasPermi('system:user:edit')")
    @Log(title = "鐢ㄦ埛绠＄悊", businessType = BusinessType.UPDATE)
    @PutMapping("/changeStatus")
    public AjaxResult changeStatus(@RequestBody SysUser user)
    {
        userService.checkUserAllowed(user);
        userService.checkUserDataScope(user.getUserId());
        user.setUpdateBy(getUsername());
        int rows = userService.updateUserStatus(user);
        if (rows > 0) tokenService.revokeUserSessions(user.getUserId());
        return toAjax(rows);
    }

    /**
     * 鏍规嵁鐢ㄦ埛缂栧彿鑾峰彇鎺堟潈瑙掕壊
     */
    @PreAuthorize("@ss.hasPermi('system:user:query')")
    @GetMapping("/authRole/{userId}")
    public AjaxResult authRole(@PathVariable("userId") Long userId)
    {
        AjaxResult ajax = AjaxResult.success();
        SysUser user = userService.selectUserById(userId);
        List<SysRole> roles = roleService.selectRolesByUserId(userId);
        ajax.put("user", user);
        ajax.put("roles", SecurityUtils.isAdmin(userId) ? roles : roles.stream().filter(r -> !r.isAdmin()).collect(Collectors.toList()));
        return ajax;
    }

    /**
     * 鐢ㄦ埛鎺堟潈瑙掕壊
     */
    @PreAuthorize("@ss.hasPermi('system:user:edit')")
    @Log(title = "鐢ㄦ埛绠＄悊", businessType = BusinessType.GRANT)
    @PutMapping("/authRole")
    public AjaxResult insertAuthRole(Long userId, Long[] roleIds)
    {
        userService.checkUserDataScope(userId);
        userService.insertUserAuth(userId, roleIds);
        tokenService.revokeUserSessions(userId);
        return success();
    }

    @PreAuthorize("@ss.hasPermi('system:user:edit')")
    @GetMapping("/deviceAuth/{userId}")
    public AjaxResult deviceAuth(@PathVariable("userId") Long userId)
    {
        SysUser targetUser = userService.selectUserById(userId);
        if (targetUser == null)
        {
            return error("用户不存在");
        }
        userService.checkUserDataScope(userId);
        AjaxResult ajax = AjaxResult.success();
        ajax.put("userId", userId);
        ajax.put("user", targetUser);
        List<PhmDeviceEntity> devices = phmService.listDevices(null);
        ajax.put("deviceIds", targetUser.isAdmin()
            ? devices.stream().map(PhmDeviceEntity::getId).collect(Collectors.toList())
            : userDeviceService.selectDeviceIdsByUserId(userId));
        ajax.put("devices", devices);
        return ajax;
    }

    @PreAuthorize("@ss.hasPermi('system:user:edit')")
    @Log(title = "用户设备权限", businessType = BusinessType.GRANT)
    @PutMapping("/deviceAuth")
    public AjaxResult updateDeviceAuth(@RequestBody SysUserDeviceAuth auth)
    {
        if (auth == null || auth.getUserId() == null)
        {
            return error("用户不能为空");
        }
        SysUser targetUser = userService.selectUserById(auth.getUserId());
        if (targetUser == null)
        {
            return error("用户不存在");
        }
        userService.checkUserAllowed(targetUser);
        userService.checkUserDataScope(auth.getUserId());
        List<PhmDeviceEntity> visibleDevices = phmService.listDevices(null);
        java.util.Set<Long> visibleDeviceIds = visibleDevices.stream()
            .map(PhmDeviceEntity::getId).collect(Collectors.toSet());
        Long[] requestedIds = auth.getDeviceIds() == null ? new Long[0] : auth.getDeviceIds();
        for (Long deviceId : requestedIds)
        {
            if (deviceId == null || !visibleDeviceIds.contains(deviceId))
            {
                return error("包含不存在或无权分配的设备");
            }
        }
        userDeviceService.replaceUserDevices(auth.getUserId(), requestedIds, getUsername());
        tokenService.revokeUserSessions(auth.getUserId());
        return success();
    }
}

