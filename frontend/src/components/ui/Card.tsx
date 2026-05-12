import React from 'react';

interface CardProps {
  children: React.ReactNode;
  header?: React.ReactNode;
  className?: string;
}

const Card: React.FC<CardProps> = ({ children, header, className = '' }) => (
  <div className={`bg-white rounded-xl shadow-sm border border-gray-200 overflow-hidden ${className}`}>
    {header && (
      <div className="px-6 py-4 border-b border-gray-200 font-semibold text-gray-800">
        {header}
      </div>
    )}
    <div className="p-6">{children}</div>
  </div>
);

export default Card;
