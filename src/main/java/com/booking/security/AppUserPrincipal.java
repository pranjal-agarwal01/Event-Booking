package com.booking.security;

import com.booking.user.Role;
import com.booking.user.User;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The authenticated caller, as Spring Security sees it. Built either from the
 * database (at login) or straight from JWT claims on every later request, with
 * no database hit - that is the whole point of a stateless token.
 */
public class AppUserPrincipal implements UserDetails {

    private final Long id;
    private final String email;
    private final String passwordHash;
    private final Role role;

    public AppUserPrincipal(Long id, String email, String passwordHash, Role role) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public static AppUserPrincipal from(User user) {
        return new AppUserPrincipal(user.getId(), user.getEmail(), user.getPasswordHash(), user.getRole());
    }

    public Long getId() {
        return id;
    }

    public Role getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // hasRole("ADMIN") looks for the authority named ROLE_ADMIN.
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
