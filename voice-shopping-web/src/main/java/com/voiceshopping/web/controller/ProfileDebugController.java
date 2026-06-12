package com.voiceshopping.web.controller;

import com.voiceshopping.business.memory.ShortTermMemory;
import com.voiceshopping.business.profile.UserProfileService;
import com.voiceshopping.common.dto.ApiResult;
import com.voiceshopping.common.dto.agent.UserProfileSnapshot;
import com.voiceshopping.common.exception.ForbiddenException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Debug endpoints for inspecting user profile snapshots and session short-term memory.
 */
@RestController
@RequestMapping("/api/v1/profile")
public class ProfileDebugController {

    private final UserProfileService profileService;
    private final ShortTermMemory shortTermMemory;

    public ProfileDebugController(UserProfileService profileService,
                                  ShortTermMemory shortTermMemory) {
        this.profileService = profileService;
        this.shortTermMemory = shortTermMemory;
    }

    @GetMapping("/{userId}")
    public ApiResult<UserProfileSnapshot> getProfile(@PathVariable long userId) {
        UserProfileSnapshot snapshot = profileService.load(userId);
        if (snapshot == null) {
            throw new ForbiddenException("权限不足");
        }
        return ApiResult.ok(snapshot);
    }

    @GetMapping("/memory/{sessionId}")
    public ApiResult<List<ShortTermMemory.Turn>> getMemory(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "20") int limit) {
        List<ShortTermMemory.Turn> turns = shortTermMemory.recent(sessionId, limit);
        return ApiResult.ok(turns);
    }
}
