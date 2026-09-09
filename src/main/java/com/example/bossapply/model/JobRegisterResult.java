package com.example.bossapply.model;

/**
 * 职位注册结果，明确返回是否为重复职位。
 */
public record JobRegisterResult(JobRecord job, boolean duplicate) {
}
