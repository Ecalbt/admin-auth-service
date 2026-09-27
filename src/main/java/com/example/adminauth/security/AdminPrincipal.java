package com.example.adminauth.security;

import com.example.adminauth.security.jwt.GrantDto;
import lombok.Builder;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Getter
@Builder
public class AdminPrincipal implements UserDetails {

    private final String id;
    private final String username;
    private final String email;
    private final String sessionId;
    private final boolean mfaVerified;
    private final List<String> roles;
    private final List<GrantDto> permissions;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        Set<GrantedAuthority> authorities = new HashSet<>();
        if (roles != null) {
            for (String r : roles) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + r));
            }
        }
        if (permissions != null) {
            for (GrantDto g : permissions) {
                authorities.add(new SimpleGrantedAuthority(g.perm()));
            }
        }
        return authorities;
    }

    @Override
    public String getPassword() {
        return null; // Stateless JWT
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
