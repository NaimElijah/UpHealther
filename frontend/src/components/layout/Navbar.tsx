import React from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../../hooks/useAuth';
import Button from '../ui/Button';

const Navbar: React.FC = () => {
  const { user, logout } = useAuth();

  return (
    <nav className="bg-white border-b border-gray-200 h-16 flex items-center px-6 justify-between sticky top-0 z-30">
      <Link to="/dashboard" className="flex items-center gap-2">
        <span className="text-2xl">💪</span>
        <span className="font-bold text-gray-900 text-lg">HealthUpgrades</span>
      </Link>
      <div className="flex items-center gap-4">
        {user && (
          <span className="text-sm text-gray-600 hidden sm:block">
            Hi, <span className="font-medium">{user.name}</span>
          </span>
        )}
        <Button variant="ghost" size="sm" onClick={logout}>
          Logout
        </Button>
      </div>
    </nav>
  );
};

export default Navbar;
