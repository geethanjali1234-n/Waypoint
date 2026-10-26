import { describe, it, expect, vi, beforeEach } from 'vitest';
import authApi from '../../api/authApi';
import apiClient from '../../api/apiClient';

vi.mock('../../api/apiClient');

describe('authApi', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('registerEmployer calls POST /auth/register/employer with the payload', () => {
    const payload = { companyName: 'Acme' };
    authApi.registerEmployer(payload);

    expect(apiClient.post).toHaveBeenCalledWith('/auth/register/employer', payload);
  });

  it('registerJobSeeker calls POST /auth/register/job-seeker with the payload', () => {
    const payload = { firstName: 'Ada' };
    authApi.registerJobSeeker(payload);

    expect(apiClient.post).toHaveBeenCalledWith('/auth/register/job-seeker', payload);
  });

  it('verifyOtp calls POST /auth/verify-otp with the payload', () => {
    const payload = { email: 'ada@example.com', otp: '123456' };
    authApi.verifyOtp(payload);

    expect(apiClient.post).toHaveBeenCalledWith('/auth/verify-otp', payload);
  });

  it('login calls POST /auth/login with the payload', () => {
    const payload = { email: 'ada@example.com', password: 'secret' };
    authApi.login(payload);

    expect(apiClient.post).toHaveBeenCalledWith('/auth/login', payload);
  });

  it('calls each password reset endpoint with its payload', () => {
    const emailPayload = { email: 'ada@example.com' };
    const otpPayload = { ...emailPayload, otp: '123456' };
    const resetPayload = { ...otpPayload, newPassword: 'new-password', confirmPassword: 'new-password' };

    authApi.forgotPassword(emailPayload);
    authApi.verifyResetOtp(otpPayload);
    authApi.resetPassword(resetPayload);

    expect(apiClient.post).toHaveBeenCalledWith('/auth/forgot-password', emailPayload);
    expect(apiClient.post).toHaveBeenCalledWith('/auth/verify-reset-otp', otpPayload);
    expect(apiClient.post).toHaveBeenCalledWith('/auth/reset-password', resetPayload);
  });
});
