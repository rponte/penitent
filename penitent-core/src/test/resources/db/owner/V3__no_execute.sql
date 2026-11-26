-- A function plsql_test sees, through DEBUG, but may not execute (#174): ALL_OBJECTS lists it, ALL_ARGUMENTS does
-- not, and Oracle's name resolution says it is not accessible.
CREATE FUNCTION NOEXEC_T(p_a NUMBER) RETURN NUMBER IS BEGIN RETURN 1; END;
/

GRANT DEBUG ON NOEXEC_T TO plsql_test;
