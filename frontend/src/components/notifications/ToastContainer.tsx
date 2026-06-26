import React from 'react';
import { useNavigate } from 'react-router-dom';
import type { AppNotification } from '../../types';
import { categoryMeta } from './notificationMeta';

export interface ToastData extends Pick<AppNotification, 'category' | 'title' | 'message' | 'relatedUpgradeId'> {
  id: string;
}

interface Props {
  toasts: ToastData[];
  onDismiss: (id: string) => void;
}

/** Transient real-time toasts, stacked top-right above everything. */
const ToastContainer: React.FC<Props> = ({ toasts, onDismiss }) => {
  const navigate = useNavigate();
  if (toasts.length === 0) return null;

  return (
    <div className="fixed top-4 right-4 z-[60] flex flex-col gap-2 w-80 max-w-[calc(100vw-2rem)]">
      {toasts.map((t) => {
        const meta = categoryMeta[t.category];
        return (
          <div
            key={t.id}
            role="status"
            className={`${meta.bg} ${meta.ring} border rounded-xl shadow-lg p-3 flex items-start gap-3 cursor-pointer animate-[fadeIn_0.2s_ease-out]`}
            onClick={() => {
              if (t.relatedUpgradeId) navigate(`/upgrades/${t.relatedUpgradeId}`);
              onDismiss(t.id);
            }}
          >
            <span className="text-lg leading-none mt-0.5">{meta.icon}</span>
            <div className="flex-1 min-w-0">
              <p className={`text-sm font-semibold ${meta.text} truncate`}>{t.title}</p>
              {t.message && <p className="text-xs text-gray-600 mt-0.5 line-clamp-2">{t.message}</p>}
            </div>
            <button
              onClick={(e) => { e.stopPropagation(); onDismiss(t.id); }}
              className="text-gray-400 hover:text-gray-600 text-lg leading-none"
              aria-label="Dismiss"
            >
              &times;
            </button>
          </div>
        );
      })}
    </div>
  );
};

export default ToastContainer;
