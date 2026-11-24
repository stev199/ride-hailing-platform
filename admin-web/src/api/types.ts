/**
 * API types - mirrors docs/api/openapi.yaml (generator-precise, D49 rules).
 * Nullability follows the contract: `string | null` only where the schema says so.
 */

export type Role =
  | 'RIDER'
  | 'DRIVER'
  | 'ADMIN_SUPER'
  | 'ADMIN_OPS'
  | 'ADMIN_SUPPORT'
  | 'ADMIN_FINANCE'
  | 'ADMIN_VERIFIER'
  | 'ADMIN_READONLY';

export type UserStatus = 'ACTIVE' | 'BANNED_TEMP' | 'BANNED_PERM' | 'DELETED';

/** /v1/me + AuthSuccess.user */
export interface User {
  id: string;
  phone: string | null;
  email: string | null;
  displayName: string;
  role: Role;
  status: UserStatus;
  locale: string;
}

/** AuthSuccess.tokens */
export interface TokenPair {
  accessToken: string;
  refreshToken: string;
  accessExpiresAtEpochSec: number;
}

/** POST /v1/auth/login/password 200 */
export interface AuthSuccess {
  user: User;
  tokens: TokenPair;
  created: boolean;
}

/** /v1/admin/users items (admin shape: createdAtEpochSec added) */
export interface AdminUser {
  id: string;
  phone: string | null;
  email: string | null;
  displayName: string;
  role: Role;
  status: UserStatus;
  locale: string;
  createdAtEpochSec: number;
}

/** GET /v1/admin/users 200 */
export interface AdminUserList {
  users: AdminUser[];
  limit: number;
  offset: number;
}

/** GET /v1/admin/audit 200 */
export interface AuditEvent {
  seq: number;
  type: string;
  occurredAt: string;
  payloadJson: string;
  hash: string;
}

export interface AuditTail {
  verifyOk: boolean;
  events: AuditEvent[];
}

/** P1-5a config service (openapi ConfigBundle / ConfigVersion / AdminConfig). */
export interface ChannelState {
  enabled: boolean;
  priority: number;
  devOnly: boolean;
}

export interface LoginMethodsState {
  passwordLoginEnabled: boolean;
  otpAutoRegister: boolean;
  otpChannels: Record<string, ChannelState>;
}

export interface ConfigVersion {
  version: number;
  issuedAtEpochSec: number;
  config: string;
  signature: string;
  changedBy: string;
  change: string;
}

/** GET /v1/admin/config/login-methods 200 */
export interface AdminConfig {
  currentVersion: number;
  loginMethods: LoginMethodsState;
  history: ConfigVersion[];
}

/** Error body per the contract (401/403/404/409/429/400). */
export interface ApiError {
  code: string;
  message: string;
  traceId: string | null;
}
