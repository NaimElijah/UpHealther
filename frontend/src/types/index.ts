export interface User {
  id: string;
  name: string;
  email: string;
  createdAt: string;
}

export interface AuthResponse {
  token: string;
  user: User;
}

export interface HealthArea {
  id: string;
  userId: string;
  name: string;
  description?: string;
  priority?: number;
  icon?: string;
  color?: string;
  createdAt: string;
  upgradeCount?: number;
}

export type UpgradeType =
  | 'HABIT'
  | 'ONE_TIME_ACTION'
  | 'PRODUCT_REPLACEMENT'
  | 'ROUTINE'
  | 'GOAL'
  | 'EXPERIMENT'
  | 'LEARNING_TASK'
  | 'MEDICAL_PREVENTIVE';

export type UpgradeStatus =
  | 'IDEA'
  | 'PLANNED'
  | 'ACTIVE'
  | 'PAUSED'
  | 'COMPLETED'
  | 'ABANDONED';

export type Difficulty = 'EASY' | 'MEDIUM' | 'HARD';

export type TrackingType = 'BOOLEAN' | 'NUMERIC' | 'TEXT' | 'RATING';

export type Frequency = 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'CUSTOM';

export interface TrackingConfig {
  id: string;
  upgradeId: string;
  trackingType: TrackingType;
  frequency: Frequency;
  targetNumericValue?: number;
  targetUnit?: string;
  requiredDaily?: boolean;
}

export interface HealthUpgrade {
  id: string;
  userId: string;
  areaId?: string;
  title: string;
  description?: string;
  type: UpgradeType;
  status: UpgradeStatus;
  difficulty: Difficulty;
  plannedStartDate?: string;
  actualStartDate?: string;
  targetEndDate?: string;
  motivation?: string;
  successCriteria?: string;
  overdue?: boolean;
  createdAt: string;
  updatedAt?: string;
  version: number;
  trackingConfig?: TrackingConfig;
}

export interface StreakSummary {
  current: number;
  longest: number;
}

export interface ProgressEntry {
  id: string;
  upgradeId: string;
  userId: string;
  date: string;
  completed?: boolean;
  numericValue?: number;
  unit?: string;
  rating?: number;
  note?: string;
  createdAt: string;
}

export interface Reflection {
  id: string;
  upgradeId: string;
  userId: string;
  date: string;
  difficultyRating?: number;
  benefitRating?: number;
  whatWorked?: string;
  whatDidNotWork?: string;
  nextAdjustment?: string;
  createdAt: string;
}

export interface DashboardDto {
  activeUpgrades: HealthUpgrade[];
  plannedUpgrades: HealthUpgrade[];
  todayUpgrades: HealthUpgrade[];
  overdueUpgrades: HealthUpgrade[];
  weeklyCompletionRate: number;
  streaks: Record<string, number>;
  recentlyCompleted: HealthUpgrade[];
  areaSummary: AreaSummary[];
}

export interface AreaSummary {
  areaId: string;
  areaName: string;
  totalUpgrades: number;
  activeCount: number;
  completedCount: number;
}

export interface CreateHealthAreaRequest {
  name: string;
  description?: string;
  priority?: number;
  icon?: string;
  color?: string;
}

export interface CreateUpgradeRequest {
  areaId?: string;
  title: string;
  description?: string;
  type: UpgradeType;
  difficulty: Difficulty;
  motivation?: string;
  successCriteria?: string;
  plannedStartDate?: string;
  targetEndDate?: string;
}

export interface CreateProgressRequest {
  upgradeId: string;
  date: string;
  completed?: boolean;
  numericValue?: number;
  unit?: string;
  rating?: number;
  note?: string;
}

export interface CreateReflectionRequest {
  upgradeId: string;
  date: string;
  difficultyRating?: number;
  benefitRating?: number;
  whatWorked?: string;
  whatDidNotWork?: string;
  nextAdjustment?: string;
}

export type NotificationType =
  | 'UPGRADE_CREATED'
  | 'UPGRADE_PLANNED'
  | 'UPGRADE_ACTIVATED'
  | 'UPGRADE_PAUSED'
  | 'UPGRADE_COMPLETED'
  | 'UPGRADE_ABANDONED'
  | 'STREAK_ACHIEVED'
  | 'UPGRADE_OVERDUE'
  | 'REFLECTION_ADDED'
  | 'REMINDER'
  | 'CHECKIN_REMINDER';

export type NotificationCategory = 'INFO' | 'SUCCESS' | 'WARNING' | 'REMINDER';

export interface AppNotification {
  id: string;
  type: NotificationType;
  category: NotificationCategory;
  title: string;
  message?: string;
  relatedUpgradeId?: string;
  read: boolean;
  createdAt: string;
}

export interface Reminder {
  id: string;
  upgradeId: string;
  reminderTime: string;   // "HH:mm" / "HH:mm:ss"
  daysOfWeek: string[];   // e.g. ["MON","WED","FRI"]; empty means every day
  enabled: boolean;
}

export interface CreateReminderRequest {
  reminderTime: string;   // "HH:mm"
  daysOfWeek?: string[];
  enabled?: boolean;
}
