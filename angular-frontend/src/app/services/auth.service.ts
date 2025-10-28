import { Injectable, signal } from '@angular/core';

@Injectable({
  providedIn: 'root'
})
export class AuthService {
  private readonly currentUser = signal<string | null>(null);

  // Simple hardcoded users
  private readonly validUsers = ['u1', 'u2', 'u3', 'u4', 'u5'];

  constructor() {
    // Check if user is already logged in from localStorage
    const storedUser = localStorage.getItem('currentUser');
    if (storedUser && this.validUsers.includes(storedUser)) {
      this.currentUser.set(storedUser);
    }
  }

  login(username: string, password: string): boolean {
    // Simple validation: username must match password and be in valid users
    if (this.validUsers.includes(username) && username === password) {
      this.currentUser.set(username);
      localStorage.setItem('currentUser', username);
      return true;
    }
    return false;
  }

  logout(): void {
    this.currentUser.set(null);
    localStorage.removeItem('currentUser');
  }

  getCurrentUser() {
    return this.currentUser.asReadonly();
  }

  isLoggedIn(): boolean {
    return this.currentUser() !== null;
  }
}
