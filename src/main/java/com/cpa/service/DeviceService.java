package com.cpa.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cpa.entity.TtAccountData;
import com.cpa.entity.TtAccountDataOutlook;
import com.cpa.repository.TtAccountDataOutlookRepository;
import com.cpa.repository.TtAccountDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 设备管理服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceService {

    private final TtAccountDataOutlookRepository outlookRepository;
    private final TtAccountDataRepository accountRepository;

    /**
     * 分页查询设备池（outlook表）
     */
    public Page<TtAccountDataOutlook> getDevicePool(Page<TtAccountDataOutlook> page, String country, String pkgName, Integer status) {
        QueryWrapper<TtAccountDataOutlook> wrapper = new QueryWrapper<>();
        
        if (country != null && !country.isEmpty()) {
            wrapper.eq("country", country);
        }
        if (pkgName != null && !pkgName.isEmpty()) {
            wrapper.eq("pkg_name", pkgName);
        }
        if (status != null) {
            wrapper.eq("status", status);
        }
        
        wrapper.orderByDesc("created_at");
        return outlookRepository.selectPage(page, wrapper);
    }

    /**
     * 分页查询账号库（主表）
     */
    public Page<TtAccountData> getAccountLibrary(Page<TtAccountData> page, String country, String pkgName, 
                                               Integer status, Integer nurtureStatus) {
        QueryWrapper<TtAccountData> wrapper = new QueryWrapper<>();
        
        if (country != null && !country.isEmpty()) {
            wrapper.eq("country", country);
        }
        if (pkgName != null && !pkgName.isEmpty()) {
            wrapper.eq("pkg_name", pkgName);
        }
        if (status != null) {
            wrapper.eq("status", status);
        }
        if (nurtureStatus != null) {
            wrapper.eq("nurture_status", nurtureStatus);
        }
        
        wrapper.orderByDesc("created_at");
        return accountRepository.selectPage(page, wrapper);
    }

    /**
     * 获取需要注册的设备列表
     */
    public List<TtAccountDataOutlook> getDevicesNeedRegister() {
        return outlookRepository.findDevicesNeedRegister();
    }

    /**
     * 获取需要养号的账号列表
     */
    public List<TtAccountData> getAccountsNeedNurture() {
        return accountRepository.findAccountsNeedNurture();
    }

    /**
     * 获取养号完成的账号列表
     */
    public List<TtAccountData> getNurturedAccounts() {
        return accountRepository.findNurturedAccounts();
    }

    /**
     * 根据phoneId查找设备
     */
    public TtAccountDataOutlook findDeviceByPhoneId(String phoneId) {
        try {
            QueryWrapper<TtAccountDataOutlook> wrapper = new QueryWrapper<>();
            wrapper.eq("phone_id", phoneId);
            return outlookRepository.selectOne(wrapper);
        } catch (Exception e) {
            log.error("查找设备失败: {}", phoneId, e);
            return null;
        }
    }

    /**
     * 添加设备到设备池
     */
    @Transactional
    public boolean addDeviceToPool(TtAccountDataOutlook device) {
        try {
            device.setCreatedAt(LocalDateTime.now());
            device.setUpdatedAt(LocalDateTime.now());
            device.setStatus(0); // 正常状态
            device.setEmailStatus(0); // 未绑定邮箱
            device.setEditStatus(0); // 未编辑
            device.setUploadStatus(0); // 不上传视频
            
            int result = outlookRepository.insert(device);
            log.info("添加设备到设备池成功，设备ID: {}", device.getPhoneId());
            return result > 0;
        } catch (Exception e) {
            log.error("添加设备到设备池失败", e);
            return false;
        }
    }

    /**
     * 注册完成后从设备池转移到账号库
     */
    @Transactional
    public boolean transferToAccountLibrary(TtAccountDataOutlook outlookDevice) {
        try {
            // 创建账号库记录
            TtAccountData account = new TtAccountData();
            account.setPhoneId(outlookDevice.getPhoneId());
            account.setPhoneServerId(outlookDevice.getPhoneServerId());
            account.setCountry(outlookDevice.getCountry());
            account.setPkgName(outlookDevice.getPkgName());
            account.setEmailAccount(outlookDevice.getEmailAccount());
            account.setEmailPassword(outlookDevice.getEmailPassword());
            account.setEmailFullname(outlookDevice.getEmailFullname());
            account.setTtUserName(outlookDevice.getTtUserName());
            account.setTtPassword(outlookDevice.getTtPassword());
            account.setTtBio(outlookDevice.getTtBio());
            account.setUrl(outlookDevice.getUrl());
            account.setNote(outlookDevice.getNote());
            account.setCreatedAt(LocalDateTime.now());
            account.setUpdatedAt(LocalDateTime.now());
            account.setStatus(0); // 正常状态
            account.setEmailStatus(1); // 已绑定邮箱
            account.setEditStatus(0); // 未编辑
            account.setUploadStatus(0); // 不上传视频
            account.setVideoDays(0); // 刷视频天数
            account.setNurtureStatus(0); // 养号中
            
            // 插入到账号库
            int insertResult = accountRepository.insert(account);
            
            if (insertResult > 0) {
                // 删除设备池记录
                outlookRepository.deleteById(outlookDevice.getId());
                log.info("设备转移成功，从设备池转移到账号库，设备ID: {}", outlookDevice.getPhoneId());
                return true;
            }
            
            return false;
        } catch (Exception e) {
            log.error("设备转移失败", e);
            return false;
        }
    }

    /**
     * 批量更新设备状态
     */
    @Transactional
    public boolean batchUpdateDeviceStatus(List<Long> ids, Integer status, String tableType) {
        try {
            if ("outlook".equals(tableType)) {
                for (Long id : ids) {
                    TtAccountDataOutlook device = new TtAccountDataOutlook();
                    device.setId(id);
                    device.setStatus(status);
                    device.setUpdatedAt(LocalDateTime.now());
                    outlookRepository.updateById(device);
                }
            } else if ("account".equals(tableType)) {
                for (Long id : ids) {
                    TtAccountData account = new TtAccountData();
                    account.setId(id);
                    account.setStatus(status);
                    account.setUpdatedAt(LocalDateTime.now());
                    accountRepository.updateById(account);
                }
            }
            
            log.info("批量更新设备状态成功，数量: {}", ids.size());
            return true;
        } catch (Exception e) {
            log.error("批量更新设备状态失败", e);
            return false;
        }
    }

    /**
     * 批量更新刷视频天数
     */
    @Transactional
    public boolean batchUpdateVideoDays(int limit) {
        try {
            int result = accountRepository.batchUpdateVideoDays(limit);
            log.info("批量更新刷视频天数成功，更新数量: {}", result);
            return result > 0;
        } catch (Exception e) {
            log.error("批量更新刷视频天数失败", e);
            return false;
        }
    }

    /**
     * 批量更新养号状态
     */
    @Transactional
    public boolean batchUpdateNurtureStatus(int days) {
        try {
            int result = accountRepository.batchUpdateNurtureStatus(days);
            log.info("批量更新养号状态成功，更新数量: {}", result);
            return result > 0;
        } catch (Exception e) {
            log.error("批量更新养号状态失败", e);
            return false;
        }
    }

    /**
     * 根据ID获取设备信息
     */
    public TtAccountDataOutlook getDeviceById(Long id) {
        return outlookRepository.selectById(id);
    }

    /**
     * 根据ID获取账号信息
     */
    public TtAccountData getAccountById(Long id) {
        return accountRepository.selectById(id);
    }

    /**
     * 更新设备信息
     */
    @Transactional
    public boolean updateDevice(TtAccountDataOutlook device) {
        try {
            device.setUpdatedAt(LocalDateTime.now());
            int result = outlookRepository.updateById(device);
            return result > 0;
        } catch (Exception e) {
            log.error("更新设备信息失败", e);
            return false;
        }
    }

    /**
     * 更新账号信息
     */
    @Transactional
    public boolean updateAccount(TtAccountData account) {
        try {
            account.setUpdatedAt(LocalDateTime.now());
            int result = accountRepository.updateById(account);
            return result > 0;
        } catch (Exception e) {
            log.error("更新账号信息失败", e);
            return false;
        }
    }
}
