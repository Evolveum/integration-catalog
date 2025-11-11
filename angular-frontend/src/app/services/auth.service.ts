import { Injectable, signal } from '@angular/core';

@Injectable({
  providedIn: 'root'
})
export class AuthService {
  private readonly _currentUser = signal<string | null>(null);

  // Expose as a readonly signal property instead of a method
  readonly currentUser = this._currentUser.asReadonly();

  // Simple hardcoded users
  private readonly validUsers = ['u1', 'u2', 'u3', 'u4', 'u5'];

  constructor() {
    // Check if user is already logged in from localStorage
    const storedUser = localStorage.getItem('currentUser');
    if (storedUser && this.validUsers.includes(storedUser)) {
      this._currentUser.set(storedUser);
    }
  }

  login(username: string, password: string): boolean {
    // Simple validation: username must match password and be in valid users
    if (this.validUsers.includes(username) && username === password) {
      this._currentUser.set(username);
      localStorage.setItem('currentUser', username);
      return true;
    }
    return false;
  }

  logout(): void {
    this._currentUser.set(null);
    localStorage.removeItem('currentUser');
  }

  isLoggedIn(): boolean {
    return this._currentUser() !== null;
  }
}
